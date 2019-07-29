/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Status}
import fr.acinq.bitcoin.{ByteVector32, Crypto, MilliSatoshi}
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, Channel}
import fr.acinq.eclair.db.IncomingPayment
import fr.acinq.eclair.payment.PaymentLifecycle.{DecryptedHtlc, ReceivePayment}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Globals, NodeParams, randomBytes32}

import scala.compat.Platform
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

/**
 * Simple payment handler that generates payment requests and fulfills incoming htlcs.
 *
 * Note that unfulfilled payment requests are kept forever if they don't have an expiry!
 *
 * Created by PM on 17/06/2016.
 */
class LocalPaymentHandler(nodeParams: NodeParams) extends Actor with ActorLogging {

  import LocalPaymentHandler._

  implicit val ec: ExecutionContext = context.system.dispatcher
  val paymentDb = nodeParams.db.payments
  var multiPartPayments = Map.empty[ByteVector32, ActorRef]

  override def receive: Receive = {

    case ReceivePayment(amount_opt, desc, expirySeconds_opt, extraHops, fallbackAddress_opt, paymentPreimage_opt, allowMultiPart) =>
      Try {
        val paymentPreimage = paymentPreimage_opt.getOrElse(randomBytes32)
        val paymentHash = Crypto.sha256(paymentPreimage)
        val expirySeconds = expirySeconds_opt.getOrElse(nodeParams.paymentRequestExpiry.toSeconds)
        val features = if (allowMultiPart) {
          Some(PaymentRequest.Features(PaymentRequest.Features.BASIC_MULTI_PART_PAYMENT_OPTIONAL))
        } else {
          None
        }
        val paymentRequest = PaymentRequest(nodeParams.chainHash, amount_opt, paymentHash, nodeParams.privateKey, desc, fallbackAddress_opt, expirySeconds = Some(expirySeconds), extraHops = extraHops, features = features)
        log.debug(s"generated payment request={} from amount={}", PaymentRequest.write(paymentRequest), amount_opt)
        paymentDb.addPaymentRequest(paymentRequest, paymentPreimage)
        paymentRequest
      } match {
        case Success(paymentRequest) => sender ! paymentRequest
        case Failure(exception) => sender ! Status.Failure(exception)
      }

    case DecryptedHtlc(htlc, perHop) =>
      paymentDb.getPendingPaymentRequestAndPreimage(htlc.paymentHash) match {
        case Some((paymentPreimage, paymentRequest)) => validatePayment(paymentRequest, htlc, perHop) match {
          case Some(failure) => sender ! failure
          case None => multiPartTotalAmount(perHop) match {
            case Some(totalAmountMsat) =>
              log.info(s"received multi-part payment for paymentHash=${htlc.paymentHash} amountMsat=${htlc.amountMsat} totalAmoutMsat=$totalAmountMsat")
              val multiPartHandler = multiPartPayments.get(htlc.paymentHash) match {
                case Some(handler) => handler
                case None =>
                  val handler = context.actorOf(MultiPartPaymentHandler.props(paymentPreimage, nodeParams.multiPartPaymentExpiry, self))
                  multiPartPayments = multiPartPayments + (htlc.paymentHash -> handler)
                  handler
              }
              multiPartHandler forward MultiPartPaymentHandler.MultiPartHtlc(totalAmountMsat, htlc)
            case None =>
              log.info(s"received payment for paymentHash=${htlc.paymentHash} amountMsat=${htlc.amountMsat}")
              // amount is correct or was not specified in the payment request
              nodeParams.db.payments.addIncomingPayment(IncomingPayment(htlc.paymentHash, htlc.amountMsat, Platform.currentTime))
              sender ! CMD_FULFILL_HTLC(htlc.id, paymentPreimage, commit = true)
              context.system.eventStream.publish(PaymentReceived(MilliSatoshi(htlc.amountMsat), htlc.paymentHash))
          }
        }
        case None =>
          sender ! CMD_FAIL_HTLC(htlc.id, Right(IncorrectOrUnknownPaymentDetails(multiPartTotalAmount(perHop).getOrElse(htlc.amountMsat))), commit = true)
      }

    case MultiPartPaymentHandler.MultiPartHtlcFailed(paymentPreimage) =>
      val paymentHash = Crypto.sha256(paymentPreimage)
      multiPartPayments.get(paymentHash).foreach(h => h ! PoisonPill)
      multiPartPayments = multiPartPayments - paymentHash

    case MultiPartPaymentHandler.MultiPartHtlcSucceeded(paymentPreimage, paidAmount) =>
      val paymentHash = Crypto.sha256(paymentPreimage)
      log.info(s"received complete multi-part payment for paymentHash=$paymentHash amountMsat=${paidAmount.amount}")
      multiPartPayments.get(paymentHash).foreach(h => h ! PoisonPill)
      multiPartPayments = multiPartPayments - paymentHash
      nodeParams.db.payments.addIncomingPayment(IncomingPayment(paymentHash, paidAmount.amount, Platform.currentTime))
      context.system.eventStream.publish(PaymentReceived(paidAmount, paymentHash))
  }

  private def validatePayment(paymentRequest: PaymentRequest, htlc: UpdateAddHtlc, perHop: OnionPerHopPayload): Option[CMD_FAIL_HTLC] = multiPartTotalAmount(perHop) match {
    case Some(totalAmountMsat) if !paymentRequest.features.allowMultiPart =>
      log.warning(s"received multi-part payment but invoice doesn't support it for paymentHash=${htlc.paymentHash} amountMsat=${htlc.amountMsat} totalAmountMsat=$totalAmountMsat")
      Some(CMD_FAIL_HTLC(htlc.id, Right(IncorrectOrUnknownPaymentDetails(totalAmountMsat)), commit = true))
    case totalAmountMsat_opt =>
      val totalAmountMsat = totalAmountMsat_opt.getOrElse(htlc.amountMsat)
      val minFinalExpiry = Globals.blockCount.get() + paymentRequest.minFinalCltvExpiry.getOrElse(Channel.MIN_CLTV_EXPIRY)
      // The htlc amount must be equal or greater than the requested amount. A slight overpaying is permitted, however
      // it must not be greater than two times the requested amount.
      // see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#failure-messages
      paymentRequest.amount match {
        case _ if paymentRequest.isExpired =>
          log.warning(s"received expired payment for paymentHash=${htlc.paymentHash} amountMsat=${htlc.amountMsat} totalAmoutMsat=$totalAmountMsat")
          Some(CMD_FAIL_HTLC(htlc.id, Right(IncorrectOrUnknownPaymentDetails(totalAmountMsat)), commit = true))
        case _ if htlc.cltvExpiry < minFinalExpiry =>
          log.warning(s"received payment with invalid expiry for paymentHash=${htlc.paymentHash} amountMsat=${htlc.amountMsat} totalAmoutMsat=$totalAmountMsat")
          Some(CMD_FAIL_HTLC(htlc.id, Right(FinalExpiryTooSoon), commit = true))
        case Some(amount) if MilliSatoshi(totalAmountMsat) < amount =>
          log.warning(s"received payment with amount too small for paymentHash=${htlc.paymentHash} amountMsat=${htlc.amountMsat} totalAmoutMsat=$totalAmountMsat")
          Some(CMD_FAIL_HTLC(htlc.id, Right(IncorrectOrUnknownPaymentDetails(totalAmountMsat)), commit = true))
        case Some(amount) if MilliSatoshi(totalAmountMsat) > amount * 2 =>
          log.warning(s"received payment with amount too large for paymentHash=${htlc.paymentHash} amountMsat=${htlc.amountMsat} totalAmoutMsat=$totalAmountMsat")
          Some(CMD_FAIL_HTLC(htlc.id, Right(IncorrectOrUnknownPaymentDetails(totalAmountMsat)), commit = true))
        case _ =>
          None
      }
  }

}

object LocalPaymentHandler {

  def props(nodeParams: NodeParams): Props = Props(new LocalPaymentHandler(nodeParams))

  private def multiPartTotalAmount(perHop: OnionPerHopPayload): Option[Long] = perHop.payload match {
    case Left(tlv) => for {
      totalAmountMsat <- tlv.records.collectFirst { case OnionTlv.MultiPartPayment(totalAmountMsat) => totalAmountMsat }
    } yield totalAmountMsat
    case _ => None
  }

}

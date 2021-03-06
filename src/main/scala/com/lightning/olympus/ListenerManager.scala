package com.lightning.olympus

import com.lightning.olympus.Utils._
import scala.collection.JavaConverters._

import com.lightning.wallet.ln.wire.{Init, LightningMessage, NodeAnnouncement}
import com.lightning.wallet.ln.{ConnectionListener, ConnectionManager, Tools}
import fr.acinq.bitcoin.{BinaryData, Transaction}
import java.net.{InetAddress, InetSocketAddress}
import rx.lang.scala.{Observable => Obs}

import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.Block
import com.lightning.olympus.database.Database
import scala.concurrent.duration.DurationInt
import scala.util.Try


class ListenerManager(db: Database) {
  def connect = ConnectionManager connectTo announce
  val announce = NodeAnnouncement(null, null, 0, values.eclairNodePubKey, null, "Routing source",
    new InetSocketAddress(InetAddress getByName values.eclairSockIp, values.eclairSockPort) :: Nil)

  ConnectionManager.listeners += new ConnectionListener {
    override def onMessage(ann: NodeAnnouncement, msg: LightningMessage) = Router receive msg
    override def onOperational(ann: NodeAnnouncement, their: Init) = Tools log "Eclair socket is operational"
    override def onTerminalError(ann: NodeAnnouncement) = ConnectionManager.connections.get(ann).foreach(_.socket.close)

    override def onDisconnect(announce: NodeAnnouncement) =
      Obs.just(Tools log "Restarting socket").delay(5.seconds)
      .subscribe(_ => connect, _.printStackTrace)
  }

  val rmSpent = "Removed spent channels"
  Blockchain.listeners += new BlockchainListener {
    override def onNewTx(twr: TransactionWithRaw) = for {
      // We need to check if any input spends a channel output
      // related payment channels should be removed

      input <- twr.tx.txIn
      chanInfo <- Router.txId2Info get input.outPoint.txid
      if chanInfo.ca.outputIndex == input.outPoint.index
    } Router.complexRemove(chanInfo :: Nil, rmSpent)

    override def onNewBlock(block: Block) = {
      val spent = Router.txId2Info.values filter Blockchain.isSpent
      if (spent.nonEmpty) Router.complexRemove(spent, rmSpent)
    }
  }

  Blockchain.listeners += new BlockchainListener {
    override def onNewTx(twr: TransactionWithRaw) = {
      val parents = twr.tx.txIn.map(_.outPoint.txid.toString)
      db.putTx(parents, twr.tx.txid.toString, twr.raw.toString)
    }

    override def onNewBlock(block: Block) = for {
      // We need to save which txids this one spends from
      // since clients will need this to extract preimages

      txid <- block.tx.asScala.par
      hex <- Try(bitcoin getRawTransactionHex txid)
      twr = TransactionWithRaw apply BinaryData(hex)
    } onNewTx(twr)
  }

  Blockchain.listeners +=
    new BlockchainListener {
      override def onNewBlock(block: Block) = for {
        // We broadcast all txs with cleared CLTV timeout
        // whose parents have at least two confirmations
        // CSV timeout will be rejected by blockchain
        // tx will be automatically removed in a week

        tx <- db getScheduled block.height
        parents = tx.txIn.map(_.outPoint.txid.toString)
        if parents forall Blockchain.isParentDeepEnough
        hex = Transaction.write(tx).toString
      } Try(bitcoin sendRawTransaction hex)
    }
}

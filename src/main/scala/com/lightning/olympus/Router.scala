package com.lightning.olympus

import com.lightning.wallet.ln._
import com.lightning.olympus.Utils._
import com.lightning.wallet.ln.wire._
import scala.collection.JavaConverters._
import com.googlecode.concurrenttrees.radix.node.concrete._

import scala.util.{Success, Try}
import rx.lang.scala.{Observable => Obs}
import com.lightning.wallet.ln.Tools.{random, wrap}
import org.jgrapht.graph.{ClassBasedEdgeFactory, DirectedMultigraph}
import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.ScriptPubKey
import com.googlecode.concurrenttrees.radix.ConcurrentRadixTree
import com.lightning.wallet.ln.RoutingInfoTag.PaymentRoute
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import scala.concurrent.duration.DurationInt
import com.lightning.wallet.ln.Tools.runAnd
import scala.language.implicitConversions
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.BinaryData
import scala.util.Random.shuffle
import scala.collection.mutable


case class ChanInfo(txid: String, key: ScriptPubKey, ca: ChannelAnnouncement)
case class ChanDirection(shortId: Long, from: PublicKey, to: PublicKey)

object Router { me =>
  type ShortChannelIdSet = Set[Long]
  type DefFactory = DefaultCharArrayNodeFactory
  type Graph = DirectedMultigraph[PublicKey, ChanDirection]
  private[this] val chanDirectionClass = classOf[ChanDirection]

  val removedChannels = mutable.Set.empty[Long]
  val chanId2Info = mutable.Map.empty[Long, ChanInfo]
  val txId2Info = mutable.Map.empty[BinaryData, ChanInfo]
  val nodeId2Announce = mutable.Map.empty[PublicKey, NodeAnnouncement]
  val searchTrie = new ConcurrentRadixTree[NodeAnnouncement](new DefFactory)
  var nodeId2Chans = Node2Channels(Map.empty withDefaultValue Set.empty)
  var finder = GraphFinder(Map.empty)

  def rmChanInfo(info: ChanInfo) = {
    // Make sure it can't be added again
    removedChannels += info.ca.shortChannelId
    nodeId2Chans = nodeId2Chans minusShortChanId info
    chanId2Info -= info.ca.shortChannelId
    txId2Info -= info.txid
  }

  def addChanInfo(info: ChanInfo) = {
    nodeId2Chans = nodeId2Chans plusShortChanId info
    chanId2Info(info.ca.shortChannelId) = info
    txId2Info(info.txid) = info
  }

  def rmNode(node: NodeAnnouncement) =
    nodeId2Announce get node.nodeId foreach { old =>
      // Announce may have a new alias so we search for
      // an old one because nodeId should remain the same
      searchTrie remove old.nodeId.toString
      searchTrie remove old.identifier
      nodeId2Announce -= old.nodeId
    }

  def addNode(node: NodeAnnouncement) = {
    searchTrie.put(node.nodeId.toString, node)
    searchTrie.put(node.identifier, node)
    nodeId2Announce(node.nodeId) = node
  }

  case class Node2Channels(dict: Map[PublicKey, ShortChannelIdSet] = Map.empty) {
    // Too big nodes have a 50% change to get dampened down, relatively well connected nodes have a 10% chance to pop up
    def between(size: Long, min: Long, max: Long, chance: Double) = random.nextDouble < chance && size > min & size < max

    lazy val scoredNodeSuggestions = dict.toSeq.map {
      case key \ chanIds if between(chanIds.size, 300, Long.MaxValue, 0.5D) => key -> chanIds.size / 10
      case key \ chanIds if between(chanIds.size, 25, 300, 0.1D) => key -> chanIds.size * 10
      case key \ chanIds => key -> chanIds.size
    }.sortWith(_._2 > _._2).map(_._1)

    def plusShortChanId(info: ChanInfo) = {
      val dict1 = dict.updated(info.ca.nodeId1, dict(info.ca.nodeId1) + info.ca.shortChannelId)
      val dict2 = dict1.updated(info.ca.nodeId2, dict1(info.ca.nodeId2) + info.ca.shortChannelId)
      Node2Channels(dict2)
    }

    def minusShortChanId(info: ChanInfo) = {
      val dict1 = dict.updated(info.ca.nodeId1, dict(info.ca.nodeId1) - info.ca.shortChannelId)
      val dict2 = dict1.updated(info.ca.nodeId2, dict1(info.ca.nodeId2) - info.ca.shortChannelId)
      val dict3 = dict2 filter { case _ \ shortChanIds => shortChanIds.nonEmpty }
      Node2Channels(dict3)
    }
  }

  case class GraphFinder(updates: Map[ChanDirection, ChannelUpdate] = Map.empty) {
    def rmEdge(graph: Graph, dir: ChanDirection) = runAnd(graph)(graph removeEdge dir)
    def rmRandomEdge(ds: Seq[ChanDirection], graph: Graph) = rmEdge(graph, shuffle(ds).head)

    def findPaths(xNodes: Set[PublicKey], xChans: ShortChannelIdSet, src: Vector[PublicKey], destination: PublicKey) = {
      val toHops: Vector[ChanDirection] => PaymentRoute = directs => for (dir <- directs) yield updates(dir) toHop dir.from
      val baseGraph = new Graph(new ClassBasedEdgeFactory(chanDirectionClass), false)
      val singleTargetChan = nodeId2Chans.dict(destination).size == 1
      val perSource = math.ceil(16D / src.size).toInt

      def find(acc: Vector[PaymentRoute], graph: Graph, limit: Int)(src: PublicKey): Vector[PaymentRoute] =
        Try apply DijkstraShortestPath.findPathBetween(graph, src, destination).getEdgeList.asScala.toVector match {
          case Success(way) if limit > 0 && singleTargetChan => find(acc :+ toHops(way), rmEdge(graph, way.head), limit - 1)(src)
          case Success(way) if limit > 0 && way.size == 1 => find(acc :+ toHops(way), rmEdge(graph, way.head), limit - 1)(src)
          case Success(way) if limit > 0 => find(acc :+ toHops(way), rmRandomEdge(way.tail, graph), limit - 1)(src)
          case Success(way) => acc :+ toHops(way)
          case _ => acc
        }

      for {
        dir @ ChanDirection(shortId, from, to) <- updates.keys
        if src.contains(from) || nodeId2Chans.dict(from).size > 1
        if to == destination || nodeId2Chans.dict(to).size > 1
        if !xChans.contains(shortId)
        if !xNodes.contains(from)
        if !xNodes.contains(to)

        _ = baseGraph addVertex to
        _ = baseGraph addVertex from
      } baseGraph.addEdge(from, to, dir)

      // Squash all route results into a single sequence, use a separate graph per source
      src flatMap find(Vector.empty, baseGraph.clone.asInstanceOf[Graph], perSource)
    }
  }

  def receive(m: LightningMessage) = me synchronized doReceive(m)
  private def doReceive(message: LightningMessage) = message match {
    case ca: ChannelAnnouncement if removedChannels.contains(ca.shortChannelId) =>
    case ca: ChannelAnnouncement => Blockchain getInfo ca foreach addChanInfo

    case node: NodeAnnouncement if node.addresses.isEmpty => Tools log s"Ignoring node without public addresses $node"
    case node: NodeAnnouncement if nodeId2Announce.get(node.nodeId).exists(_.timestamp >= node.timestamp) => Tools log s"Outdated $node"
    case node: NodeAnnouncement if !nodeId2Chans.dict.contains(node.nodeId) => Tools log s"Ignoring node without channels $node"
    case node: NodeAnnouncement => wrap(me addNode node)(me rmNode node) // Might be an update

    case cu: ChannelUpdate if cu.flags.data.size != 2 => Tools log s"Ignoring invalid flags length ${cu.flags.data.size}"
    case cu: ChannelUpdate if !chanId2Info.contains(cu.shortChannelId) => Tools log s"Ignoring update without channels $cu"
    case cu: ChannelUpdate if isOutdated(cu) => Tools log s"Ignoring outdated update $cu"

    case cu: ChannelUpdate =>
      val info = chanId2Info(cu.shortChannelId)
      val isDisabled = Announcements isDisabled cu.flags
      val direction = Announcements isNode1 cu.flags match {
        case true => ChanDirection(cu.shortChannelId, info.ca.nodeId1, info.ca.nodeId2)
        case false => ChanDirection(cu.shortChannelId, info.ca.nodeId2, info.ca.nodeId1)
      }

      val isFresh = finder.updates.get(direction).forall(existing => existing.timestamp < cu.timestamp)
      val updates1 = if (isDisabled) finder.updates - direction else finder.updates.updated(direction, cu)
      if (isFresh) finder = finder.copy(updates = updates1)

    case _ =>
  }

  def complexRemove(infos: Iterable[ChanInfo], why: String) = me synchronized {
    // Once channel infos are removed we may have nodes without channels and updates

    infos foreach rmChanInfo
    // Removal may result in lost nodes so all nodes with now zero channels are removed
    nodeId2Announce.filterKeys(nodeId => nodeId2Chans.dict(nodeId).isEmpty).values foreach rmNode
    // And finally we need to remove all the lost updates which have no channel announcements left
    val updates1 = finder.updates filter { case direction \ _ => chanId2Info contains direction.shortId }
    finder = GraphFinder(updates1)
    Tools log why
  }

  def isOutdated(cu: ChannelUpdate) =
    // Considered outdated if it older than two weeks
    cu.timestamp < System.currentTimeMillis / 1000 - 1209600

  Obs.interval(5.minutes) foreach { _ =>
    // Affects the next check: removing channels will render affected nodes outdated
    val updates1 = finder.updates filterNot { case _ \ upd => me isOutdated upd }
    finder = GraphFinder(updates1)
  }

  Obs interval 15.minutes foreach { _ =>
    val twoWeeksBehind = bitcoin.getBlockCount - 2016 // ~2 weeks
    val shortId2Updates = finder.updates.values.groupBy(_.shortChannelId)
    val oldChanInfos = chanId2Info.values.filter(_.ca.blockHeight < twoWeeksBehind)
    val outdatedChanInfos = oldChanInfos.filterNot(shortId2Updates contains _.ca.shortChannelId)
    complexRemove(outdatedChanInfos, "Removed possible present outdated nodes and channels")
  }
}
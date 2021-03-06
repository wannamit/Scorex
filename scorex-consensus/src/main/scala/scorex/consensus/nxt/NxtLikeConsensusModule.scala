package scorex.consensus.nxt

import com.google.common.primitives.Longs
import scorex.account.{Account, PrivateKeyAccount, PublicKeyAccount}
import scorex.block.{Block, BlockField}
import scorex.consensus.{OneGeneratorConsensusModule, ConsensusModule, PoSConsensusModule}
import scorex.crypto.hash.FastCryptographicHash._
import scorex.transaction._
import scorex.utils.{NTP, ScorexLogging}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Try}


class NxtLikeConsensusModule(AvgDelay: Duration = 5.seconds) extends PoSConsensusModule[NxtLikeConsensusBlockData]
with OneGeneratorConsensusModule with ScorexLogging {

  import NxtLikeConsensusModule._

  implicit val consensusModule: ConsensusModule[NxtLikeConsensusBlockData] = this

  val version = 2: Byte

  val MinBlocktimeLimit = normalize(53)
  val MaxBlocktimeLimit = normalize(67)
  val BaseTargetGamma = normalize(64)
  override val generatingBalanceDepth = EffectiveBalanceDepth

  private def avgDelayInSeconds: Long = AvgDelay.toSeconds

  private def normalize(value: Long): Double = value * avgDelayInSeconds / (60: Double)

  override def isValid[TT](block: Block)(implicit transactionModule: TransactionModule[TT]): Boolean = Try {

    val history = transactionModule.blockStorage.history

    val blockTime = block.timestampField.value

    val prev = history.parent(block).get
    val prevTime = prev.timestampField.value

    val prevBlockData = consensusBlockData(prev)
    val blockData = consensusBlockData(block)
    val generator = block.signerDataField.value.generator

    //check baseTarget
    val cbt = calcBaseTarget(prev, blockTime)
    val bbt = blockData.baseTarget
    require(cbt == bbt, s"Block's basetarget is wrong, calculated: $cbt, block contains: $bbt")

    //check generation signature
    val calcGs = calcGeneratorSignature(prevBlockData, generator)
    val blockGs = blockData.generationSignature
    require(calcGs.sameElements(blockGs),
      s"Block's generation signature is wrong, calculated: ${calcGs.mkString}, block contains: ${blockGs.mkString}")

    //check hit < target
    calcHit(prevBlockData, generator) < calcTarget(prev, blockTime, generatingBalance(generator))
  }.recoverWith { case t =>
    log.error("Error while checking a block", t)
    Failure(t)
  }.getOrElse(false)


  override def generateNextBlock[TT](account: PrivateKeyAccount)
                                    (implicit transactionModule: TransactionModule[TT]): Future[Option[Block]] = {

    val lastBlock = transactionModule.blockStorage.history.lastBlock
    val lastBlockKernelData = consensusBlockData(lastBlock)

    val lastBlockTime = lastBlock.timestampField.value

    val currentTime = NTP.correctedTime()
    val effBalance = generatingBalance(account)

    val h = calcHit(lastBlockKernelData, account)
    val t = calcTarget(lastBlock, currentTime, effBalance)

    val eta = (currentTime - lastBlockTime) / 1000

    log.debug(s"hit: $h, target: $t, generating ${h < t}, eta $eta, " +
      s"account:  $account " +
      s"account balance: $effBalance"
    )

    if (h < t) {
      val btg = calcBaseTarget(lastBlock, currentTime)
      val gs = calcGeneratorSignature(lastBlockKernelData, account)
      val consensusData = new NxtLikeConsensusBlockData {
        override val generationSignature: Array[Byte] = gs
        override val baseTarget: Long = btg
      }

      val unconfirmed = transactionModule.packUnconfirmed()
      log.debug(s"Build block with ${unconfirmed.asInstanceOf[Seq[Transaction]].size} transactions")
      log.debug(s"Block time interval is $eta seconds ")

      Future(Some(Block.buildAndSign(version,
        currentTime,
        lastBlock.uniqueId,
        consensusData,
        unconfirmed,
        account)))

    } else Future(None)
  }

  private def calcGeneratorSignature(lastBlockData: NxtLikeConsensusBlockData, generator: PublicKeyAccount) =
    hash(lastBlockData.generationSignature ++ generator.publicKey)

  private def calcHit(lastBlockData: NxtLikeConsensusBlockData, generator: PublicKeyAccount): BigInt =
    BigInt(1, calcGeneratorSignature(lastBlockData, generator).take(8).reverse)

  /**
   * BaseTarget calculation algorithm fixing the blocktimes.
   */
  private def calcBaseTarget[TT](prevBlock: Block, timestamp: Long)
                                (implicit transactionModule: TransactionModule[TT]): Long = {
    val history = transactionModule.blockStorage.history
    val height = history.heightOf(prevBlock).get
    val prevBaseTarget = consensusBlockData(prevBlock).baseTarget
    if (height % 2 == 0) {
      val blocktimeAverage = history.parent(prevBlock, AvgBlockTimeDepth - 1)
        .map(b => (timestamp - b.timestampField.value) / AvgBlockTimeDepth)
        .getOrElse(timestamp - prevBlock.timestampField.value) / 1000

      val baseTarget = (if (blocktimeAverage > avgDelayInSeconds) {
        (prevBaseTarget * Math.min(blocktimeAverage, MaxBlocktimeLimit)) / avgDelayInSeconds
      } else {
        prevBaseTarget - prevBaseTarget * BaseTargetGamma *
          (avgDelayInSeconds - Math.max(blocktimeAverage, MinBlocktimeLimit)) / (avgDelayInSeconds * 100)
      }).toLong
      bounded(baseTarget, 1, Long.MaxValue).toLong
    } else {
      prevBaseTarget
    }
  }

  protected def calcTarget(prevBlock: Block,
                           timestamp: Long,
                           effBalance: Long)(implicit transactionModule: TransactionModule[_]): BigInt = {
    val prevBlockData = consensusBlockData(prevBlock)
    val prevBlockTimestamp = prevBlock.timestampField.value

    val eta = (timestamp - prevBlockTimestamp) / 1000 //in seconds

    BigInt(prevBlockData.baseTarget) * eta * effBalance
  }

  private def bounded(value: BigInt, min: BigInt, max: BigInt): BigInt =
    if (value < min) min else if (value > max) max else value

  override def parseBytes(bytes: Array[Byte]): Try[BlockField[NxtLikeConsensusBlockData]] = Try {
    NxtConsensusBlockField(new NxtLikeConsensusBlockData {
      override val baseTarget: Long = Longs.fromByteArray(bytes.take(BaseTargetLength))
      override val generationSignature: Array[Byte] = bytes.takeRight(GeneratorSignatureLength)
    })
  }

  override def blockScore(block: Block)(implicit transactionModule: TransactionModule[_]): BigInt = {
    val baseTarget = consensusBlockData(block).baseTarget
    BigInt("18446744073709551616") / baseTarget
  }.ensuring(_ > 0)

  override def generators(block: Block): Seq[Account] = Seq(block.signerDataField.value.generator)

  override def genesisData: BlockField[NxtLikeConsensusBlockData] =
    NxtConsensusBlockField(new NxtLikeConsensusBlockData {
      override val baseTarget: Long = 153722867
      override val generationSignature: Array[Byte] = Array.fill(32)(0: Byte)
    })

  override def formBlockData(data: NxtLikeConsensusBlockData): BlockField[NxtLikeConsensusBlockData] =
    NxtConsensusBlockField(data)

  override def consensusBlockData(block: Block): NxtLikeConsensusBlockData = block.consensusDataField.value match {
    case b: NxtLikeConsensusBlockData => b
    case m => throw new AssertionError(s"Only NxtLikeConsensusBlockData is available, $m given")
  }
}


object NxtLikeConsensusModule {
  val BaseTargetLength = 8
  val GeneratorSignatureLength = 32

  val EffectiveBalanceDepth: Int = 50
  val AvgBlockTimeDepth: Int = 3
}

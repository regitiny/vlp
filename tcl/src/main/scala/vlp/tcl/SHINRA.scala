package vlp.tcl

import org.json4s.jackson.Serialization
import org.json4s._
import java.nio.file.{Paths, Files, StandardOpenOption}
import java.io.{FileOutputStream, OutputStreamWriter, PrintWriter}
import java.nio.charset.StandardCharsets
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkContext
import org.apache.spark.ml.classification.{LogisticRegression, MultilayerPerceptronClassifier}
import org.apache.spark.ml.feature._
import org.apache.spark.ml.linalg.DenseVector
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.mllib.evaluation.MulticlassMetrics
import org.apache.spark.sql.{Dataset, DataFrame, RowFactory, SparkSession}
import org.apache.spark.sql.types.{DataType, StringType, StructField, StructType}
import org.apache.spark.sql.functions.lit
import org.json4s.NoTypeHints
import org.json4s.jackson.Serialization
import org.slf4j.LoggerFactory
import scopt.OptionParser
import vlp.tok.SentenceDetection
import org.apache.spark.ml.classification.RandomForestClassifier
import java.io.File

/*
 * 
 * phuonglh, July 2020.
 * 
 */

case class WikiPage(id: String = "", 
  text: String = "", 
  title: String = "", 
  category: String = "", 
  outgoingLink: String = "", 
  redirect: String = "", 
  clazz: String = ""
) {
  val cs = this.getClass().getConstructors()
  def fromSeq(xs: Array[String]) = cs(0).newInstance(xs: _*).asInstanceOf[WikiPage]
}

class SHINRA(val sparkContext: SparkContext, val config: ConfigTCL) {
  final val logger = LoggerFactory.getLogger(getClass.getName)
  val sparkSession = SparkSession.builder().getOrCreate()
  import sparkSession.implicits._

  def createDataset(path: String, numberOfSentences: Int = Int.MaxValue): Dataset[WikiPage] = {
    val dataset = sparkSession.read.json(path).as[WikiPage]
    dataset.map { wp => 
      WikiPage(wp.id, 
        wp.text.split("""[\s.,?;:!'")(\u200b^“”'~`-]+""").take(100).mkString(" "),
        wp.title,
        wp.category,
        wp.outgoingLink,
        wp.redirect,
        wp.clazz
      )
    }
  }

  def train(dataset: Dataset[WikiPage]): PipelineModel = {
    dataset.cache()
    // create pipeline
    val tokenizer = new Tokenizer().setInputCol(config.inputColumnName).setOutputCol("tokens")
    val stopWordsRemover = new StopWordsRemover().setInputCol("tokens").setOutputCol("unigrams").setStopWords(StopWords.punctuations)
    val unigramCounter = new CountVectorizer().setInputCol("unigrams").setOutputCol("us").setMinDF(config.minFrequency).setVocabSize(config.numFeatures)
    val labelIndexer = new StringIndexer().setInputCol("category").setHandleInvalid("skip").setOutputCol("label")
    val classifierType = config.classifier
    val pipeline = if (classifierType == "mlr") {
      val bigram = new NGram().setInputCol("unigrams").setOutputCol("bigrams").setN(2)
      val bigramCounter = new CountVectorizer().setInputCol("bigrams").setOutputCol("bs").setMinDF(config.minFrequency).setVocabSize(2*config.numFeatures)
      val assembler = new VectorAssembler().setInputCols(Array("us", "bs")).setOutputCol("features")
      val mlr = new LogisticRegression().setMaxIter(config.iterations).setRegParam(config.lambda).setStandardization(false)
      new Pipeline().setStages(Array(labelIndexer, tokenizer, stopWordsRemover, unigramCounter, bigram, bigramCounter, assembler, mlr))
    } else if (classifierType == "mlp") {
      val featureHashing = new HashingTF().setInputCol("unigrams").setOutputCol("features").setNumFeatures(config.numFeatures).setBinary(true)
      val numLabels = labelIndexer.fit(dataset).labels.size
      logger.info(s"numLabels = ${numLabels}")
      val xs = config.hiddenUnits.trim
      val hiddenLayers = if (xs.nonEmpty) xs.split("[\\s,]+").map(_.toInt); else Array[Int]()
      val layers = Array(config.numFeatures) ++ hiddenLayers ++ Array[Int](numLabels)
      logger.info(layers.mkString(", "))
      val mlp = new MultilayerPerceptronClassifier().setMaxIter(config.iterations).setBlockSize(config.batchSize).setSeed(123).setLayers(layers)
      new Pipeline().setStages(Array(labelIndexer, tokenizer, stopWordsRemover, featureHashing, mlp))
    } else if (classifierType == "rfc") {
      val featureHashing = new HashingTF().setInputCol("unigrams").setOutputCol("features").setNumFeatures(config.numFeatures).setBinary(false)
      val rfc = new RandomForestClassifier().setNumTrees(config.numTrees).setMaxDepth(config.maxDepth)
      new Pipeline().setStages(Array(labelIndexer, tokenizer, stopWordsRemover, featureHashing, rfc))
    } else {
      logger.error("Not support classifier type: " + classifierType)
      new Pipeline()
    }
    logger.info("#(documents) = " + dataset.count())
    logger.info("Training process started. Please wait...")
    val model = pipeline.fit(dataset)
    model.write.overwrite().save(config.modelPath + "/" + classifierType)
    model
  }

  def eval(model: PipelineModel, dataset: Dataset[WikiPage]): Unit = {
    val transformer = model.stages(3)
    if (transformer.isInstanceOf[CountVectorizerModel]) {
      val vocabulary = transformer.asInstanceOf[CountVectorizerModel].vocabulary
      logger.info("#(vocabulary) = " + vocabulary.size)
    } else if (transformer.isInstanceOf[HashingTF]) {
        val numFeatures = transformer.asInstanceOf[HashingTF].getNumFeatures
        logger.info("#(numFeatures) = " + numFeatures)
    } else logger.error(s"Error in reading information from ${transformer.getClass.getName}")

    dataset.show(10)
    val outputDF = model.transform(dataset)

    import sparkSession.implicits._
    val predictionAndLabels = outputDF.select("label", "prediction").map(row => (row.getDouble(0), row.getDouble(1))).rdd
    val metrics = new MulticlassMetrics(predictionAndLabels)
    val scores = (metrics.accuracy, metrics.weightedFMeasure)
    logger.info(s"scores = $scores")
    if (config.verbose) {
      outputDF.show(10)
      val labels = metrics.labels
      labels.foreach(label => {
        val sb = new StringBuilder()
        sb.append(s"Precision($label) = " + metrics.precision(label) + ", ")
        sb.append(s"Recall($label) = " + metrics.recall(label) + ", ")
        sb.append(s"F($label) = " + metrics.fMeasure(label))
        logger.info(sb.toString)
      })
    }
  }

  def eval(dataset: Dataset[WikiPage]): Unit = {
    val model = PipelineModel.load(config.modelPath + "/" + config.classifier.toLowerCase())
    eval(model, dataset)
  }

  def test(dataset: Dataset[WikiPage], outputFile: String): Unit = {
    val model = PipelineModel.load(config.modelPath + "/" + config.classifier.toLowerCase())
    val outputDF = model.transform(dataset)
    import sparkSession.implicits._
    val prediction = outputDF.select("category", config.inputColumnName, "prediction", "probability", "id")
      .map(row => (row.getString(0), row.getString(1), row.getDouble(2).toInt, row.getAs[DenseVector](3)))
      .collect()
    val writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8), true)
    val labels = model.stages(0).asInstanceOf[StringIndexerModel].labels
    prediction.foreach(pair => {
      writer.print(pair._1)
      writer.print("\t")
      writer.print(pair._2)
      writer.print("\t")
      writer.print(labels(pair._3))
      writer.print("\t")
      writer.println(pair._4.toArray(pair._3))
    })
    writer.close()
  }

  /**
    * Predicts a document and outputs a probability distribution of
    * categories.
    * @param document a document
    * @param model the pipeline model
    * @return a probability distribution
    */
  def predict(document: WikiPage, model: PipelineModel): Map[String, Double] = {
    val xs = List(document)
    val labels = model.stages(0).asInstanceOf[StringIndexerModel].labels
    import sparkSession.implicits._
    val dataset = sparkSession.createDataset(xs).as[WikiPage]
    val outputDF = model.transform(dataset)
    outputDF.select("probability")
      .map(row => row.getAs[DenseVector](0).values).head()
      .zipWithIndex
      .map(pair => (if (pair._2 < labels.size) labels(pair._2) else "NA", pair._1)).toMap
  }

  def predict(inputFile: String, outputFile: String): Unit = {
    val lines = scala.io.Source.fromFile(inputFile)("UTF-8").getLines.toList.filter(_.trim.nonEmpty)
    val xs = lines.map { line =>
      val tokens = vlp.tok.Tokenizer.tokenize(line).map(_._3)
      Document("NA", tokens.mkString(" "), "NA")
    }
    import sparkSession.implicits._
    val data = sparkSession.createDataset(xs).as[WikiPage]
    test(data, outputFile)
  }

}

object SHINRA {

  def text2Json(inputPath: String, outputPath: String): Unit = {
    val lines = scala.io.Source.fromFile(inputPath).getLines().toList
    val pages = lines.par.map{ line => 
      val parts = line.split("\t")
      WikiPage().fromSeq(parts)
    }.toList
    implicit val formats = Serialization.formats(NoTypeHints)
    val content = Serialization.writePretty(pages)
    Files.write(Paths.get(outputPath), content.getBytes, StandardOpenOption.CREATE)
  }

  def main(args: Array[String]): Unit = {
    Logger.getLogger("org.apache.spark").setLevel(Level.WARN)
    val logger = LoggerFactory.getLogger(getClass.getName)

    val parser = new OptionParser[ConfigTCL]("vlp.tcl") {
      head("vlp.tcl", "1.0")
      opt[String]('M', "master").action((x, conf) => conf.copy(master = x)).text("Spark master, default is local[*]")
      opt[String]('m', "mode").action((x, conf) => conf.copy(mode = x)).text("running mode, either eval/train/test")
      opt[Unit]('v', "verbose").action((_, conf) => conf.copy(verbose = true)).text("verbose mode")
      opt[String]('c', "classifier").action((x, conf) => conf.copy(classifier = x)).text("classifier, either mlr/mlp/rfc")
      opt[Int]('b', "batchSize").action((x, conf) => conf.copy(batchSize = x)).text("batch size")
      opt[String]('h', "hiddenUnits").action((x, conf) => conf.copy(hiddenUnits = x)).text("hidden units in MLP")
      opt[Int]('f', "minFrequency").action((x, conf) => conf.copy(minFrequency = x)).text("min feature frequency")
      opt[Int]('u', "numFeatures").action((x, conf) => conf.copy(numFeatures = x)).text("number of features")
      opt[Int]('t', "numTrees").action((x, conf) => conf.copy(numTrees = x)).text("number of trees if using RFC, default is 256")
      opt[Int]('e', "maxDepth").action((x, conf) => conf.copy(maxDepth = x)).text("max tree depth if using RFC, default is 15")
      opt[String]('d', "dataPath").action((x, conf) => conf.copy(dataPath = x)).text("data path")
      opt[Double]('n', "percentage").action((x, conf) => conf.copy(percentage = x)).text("percentage of the training data to use, default is 1.0")
      opt[String]('p', "modelPath").action((x, conf) => conf.copy(modelPath = x)).text("model path, default is 'dat/tcl/'")
      opt[String]('j', "inputColumnName").action((x, conf) => conf.copy(inputColumnName = x)).text("input column name")
      opt[String]('i', "input").action((x, conf) => conf.copy(input = x)).text("input path")
      opt[String]('o', "output").action((x, conf) => conf.copy(output = x)).text("output path")
    }
    parser.parse(args, ConfigTCL()) match {
      case Some(config) =>
        val sparkSession = SparkSession.builder().appName(getClass.getName).master(config.master).getOrCreate()
        implicit val formats = Serialization.formats(NoTypeHints)
        logger.info(Serialization.writePretty(config))
        val app = new SHINRA(sparkSession.sparkContext, config)
        config.mode match {
          case "json" =>            
            text2Json(config.input, config.output) // convert tab-delimited text format to pretty multiline JSON
          case "train" => {
            val dataset = app.createDataset(config.dataPath)
            val trainingSet = if (config.percentage < 1.0) dataset.sample(config.percentage) else dataset
            trainingSet.select(config.inputColumnName).show(false)
            val model = app.train(trainingSet)
            app.eval(model, trainingSet)
          }
          case "predict" => app.predict(config.input, config.output)
          case "eval" =>
            val numberOfSentences = 5
            val model = PipelineModel.load(config.modelPath + "/" + config.classifier.toLowerCase())
            val devDataset = app.createDataset("/opt/data/shinra/dev/")
            val testDataset = app.createDataset("/opt/data/shinra/test/")
            app.eval(model, devDataset)
            app.eval(model, testDataset)
        }
        sparkSession.stop()
      case None =>
      }
  }
}
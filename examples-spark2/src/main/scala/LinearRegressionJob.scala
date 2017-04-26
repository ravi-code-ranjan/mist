
import io.hydrosphere.mist.lib.spark2._
import io.hydrosphere.mist.lib.spark2.ml._
import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.ml.regression.LinearRegression

object LinearRegressionJob extends MLMistJob with SQLSupport {
  def train(savePath: String, datasetPath: String): Map[String, Any] = {
    val df = session.read.format("libsvm").load(datasetPath)

    val lr = new LinearRegression()
      .setMaxIter(10)
      .setRegParam(0.3)
      .setElasticNetParam(0.8)
    val pipeline = new Pipeline().setStages(Array(lr))

    val model = pipeline.fit(df)

    model.write.overwrite().save(savePath)
    Map.empty
  }

  def serve(modelPath: String, features: List[List[Double]]): Map[String, Any] = {
    import LocalPipelineModel._

    val pipeline = PipelineLoader.load(modelPath)
    val data = LocalData(LocalDataColumn("features", features.map(_.toArray).map(Vectors.dense)))

    val result: LocalData = pipeline.transform(data)
    Map("result" -> result.select("prediction").toMapList)
  }
}
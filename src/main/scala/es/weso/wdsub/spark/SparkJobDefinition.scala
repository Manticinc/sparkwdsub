package es.weso.wdsub.spark

import es.weso.wdsub.spark.pschema.{PSchema, Shaped}
import es.weso.wdsub.spark.simpleshex.{CompactFormat, Reason, Schema, ShapeLabel, Start}
import es.weso.wdsub.spark.wbmodel.{Entity, LineParser, PropertyId, Statement, ValueWriter}
import org.apache.spark.graphx.{EdgeTriplet, Graph, VertexId}
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.SparkSession

import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Calendar

@SerialVersionUID(100L)
class SparkJobDefinition(sparkJobConfig: SparkJobConfig) extends Serializable {

  // Create the result file for later use.
  val resultFile = new ResultFile()
  val date = Calendar.getInstance().getTime();
  val dateFormat = new SimpleDateFormat("yyyy-mm-dd hh:mm:ss");
  val strDate = dateFormat.format(date);
  resultFile.jobName = sparkJobConfig.jobName.apply()
  resultFile.jobDate = strDate

  // Create the spark context that will be initialized depending on the job mode.
  @transient var sparkContext: SparkContext = null
  if( SparkJobDefinitionMode.fromString(sparkJobConfig.jobMode.apply()).equals(SparkJobDefinitionMode.Test) ) {
    sparkContext = SparkSession.builder().master("local[*]").appName(sparkJobConfig.jobName.apply()).getOrCreate().sparkContext
  } else if( SparkJobDefinitionMode.fromString(sparkJobConfig.jobMode.apply()).equals(SparkJobDefinitionMode.Cluster) ) {
    sparkContext = new SparkContext( new SparkConf().setAppName( sparkJobConfig.jobName.apply()) )
  }

  // Start measuring the execution time.
  val jobStartTime = System.nanoTime

  // Load the dump in to an RDD of type String. The RDD will be composed of each single line as a String.
  val dumpLines: RDD[String] = sparkContext.textFile( sparkJobConfig.jobInputDump.apply() )
  //resultFile.jobResults = ""+"Dump Lines -> " + dumpLines.count().toString

  val lineParser = LineParser()
  val graph = lineParser.dumpRDD2Graph(dumpLines, sparkContext)

  //resultFile.jobResults += "\n Graph Edges: " + graph.edges.count()
  //resultFile.jobResults += "\n Graph Vertices: " + graph.vertices.count()

  val initialLabel = Start
  val schemaString = sparkContext.textFile(sparkJobConfig.jobInputSchema.apply()).toLocalIterator.mkString
  val schema = Schema.unsafeFromString2(schemaString, CompactFormat)

  resultFile.jobResults += s"\n$schema"

  val validatedGraph: Graph[Shaped[Entity,ShapeLabel,Reason,PropertyId], Statement] =
    PSchema[Entity,Statement,ShapeLabel,Reason, PropertyId](
      graph, initialLabel, 20, false)(
      schema.checkLocal,schema.checkNeighs,schema.getTripleConstraints,_.id
    )

  val subGraph =
    validatedGraph
      .subgraph(filterEdges,filterVertices)

  val result =
    graph2rdd(
      subGraph
        .mapVertices{ case (_,v) => v.value }
    )


  // Get the job execution time in seconds.
  val jobExecutionTime = (System.nanoTime - jobStartTime) / 1e9d
  val jobCores = java.lang.Runtime.getRuntime.availableProcessors * ( sparkContext.statusTracker.getExecutorInfos.length -1 )
  val jobMem = java.lang.Runtime.getRuntime.totalMemory() * ( sparkContext.statusTracker.getExecutorInfos.length -1 )

  resultFile.time = jobExecutionTime.toString
  resultFile.cores = jobCores.toString
  resultFile.mem = jobMem.toString

  result.saveAsTextFile(s"${sparkJobConfig.jobOutputDir.apply()}/${sparkContext.applicationId}_${sparkJobConfig.jobName.apply()}_out_result")
  resultFile.jobResults += s"\nResult: ${result.count()} lines."

  sparkContext.parallelize(Seq(resultFile.toString()), 1)
    .saveAsTextFile(s"${sparkJobConfig.jobOutputDir.apply()}/${sparkContext.applicationId}_${sparkJobConfig.jobName.apply()}_out")


  // ------- UTILITY METHODS ---------

  def graph2rdd(g: Graph[Entity,Statement]): RDD[String] =
    g.vertices.map(_._2).map(ValueWriter.entity2JsonStr(_))

  def filterEdges(t: EdgeTriplet[Shaped[Entity,ShapeLabel,Reason,PropertyId], Statement]): Boolean = {
    t.srcAttr.okShapes.nonEmpty
  }

  def filterVertices(id: VertexId, v: Shaped[Entity,ShapeLabel,Reason,PropertyId]): Boolean = {
    v.okShapes.nonEmpty
  }


  def containsValidShapes(pair: (VertexId, Shaped[Entity,ShapeLabel,Reason, PropertyId])): Boolean = {
    val (_,v) = pair
    v.okShapes.nonEmpty
  }

  def getIdShapes(pair: (VertexId, Shaped[Entity,ShapeLabel,Reason, PropertyId])): (String, Set[String], Set[String]) = {
    val (_,v) = pair
    (v.value.entityId.id, v.okShapes.map(_.name), v.noShapes.map(_.name))
  }
}

package net.sansa_stack.owl.spark.rdd

import org.apache.spark.sql.{Dataset, Encoders, SparkSession}
import org.apache.spark.{Partition, SparkContext, TaskContext}
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxOWLParserFactory
import org.semanticweb.owlapi.io.{OWLParserException, StringDocumentSource}
import org.semanticweb.owlapi.model.OWLAxiom


class FunctionalSyntaxOWLAxiomsRDD(
                                    @transient val sc: SparkContext,
                                    parent: OWLExpressionsRDD) extends OWLAxiomsRDD(sc, parent) {

  private def parser = new OWLFunctionalSyntaxOWLParserFactory().createParser()
  private def man = OWLManager.createOWLOntologyManager()
  private def ontConf = man.getOntologyLoaderConfiguration
  @transient private lazy val spark = {
    SparkSession.builder().appName(sc.appName).getOrCreate()
  }

  /**
    * Builds a snipped conforming to the OWL functional syntax which then can
    * be parsed by the OWLAPI functional syntax parser. A single expression,
    * e.g.
    *
    * Declaration(Class(bar:Cls2))
    *
    * has thus to be wrapped into an ontology declaration as follows
    *
    * Ontology( <http://the.ontology.uri#>
    * Declaration(Class(bar:Cls2))
    * )
    *
    * @param expression A String containing an expression in OWL functional
    *                   syntax, e.g. Declaration(Class(bar:Cls2))
    * @return The parsed axiom or null in case something went wrong during parsing
    */
  def makeAxiom(expression: String): OWLAxiom = {
    val ontStr = "Ontology(<" + parent.ontURI + ">\n"
    val axStr = ontStr + expression + "\n)"

    val ont = man.createOntology()

    parser.parse(new StringDocumentSource(axStr), ont, ontConf)

    val it = ont.axioms().iterator()

    if (it.hasNext) {
      it.next()
    } else {
      log.warn("No axiom was created for expression " + expression)
      null
    }
  }

  override def compute(split: Partition, context: TaskContext): Iterator[OWLAxiom] = {
    parent.compute(split, context).map(line => {
      try makeAxiom(line)
      catch {
        case ex: OWLParserException => {
          log.warn("Parser error for line " + line + ": " + ex.getMessage)
          null
        }
      }
    }).filter(axiom => axiom != null)
  }

  override protected def getPartitions: Array[Partition] = {
    val numParentPartitions = parent.numPartitions
    val partitions = new Array[Partition](numParentPartitions)

    for (i <- 0 until numParentPartitions) {
      partitions(i) = new OWLAxiomsPartition(i)
    }

    partitions
  }

  override def asDataset: Dataset[OWLAxiom] = {
    // FIXME: Patrick: AFAIU this is the worst solution for serialization but there is not better one ATM
    implicit val enc = Encoders.kryo[OWLAxiom]
    spark.sqlContext.createDataset[OWLAxiom](this)
  }
}

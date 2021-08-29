package es.weso.wbmodel
import es.weso.graphxhelpers._
import es.weso.graphxhelpers.GraphBuilder._
import es.weso.rdf.nodes._
import org.apache.spark.graphx._
import cats.implicits._
import cats._
import es.weso.rdf.nodes._

object Utils {
  
  def splitIri(iri: IRI): (String, String) = {
    val iriStr = iri.getLexicalForm
    val separator = iriStr.lastIndexOf('/') + 1;
    try {
      (iriStr.substring(separator), iriStr.substring(0, separator))   
    } catch {
      case e: IllegalArgumentException => throw new IllegalArgumentException("Invalid Wikibase entity IRI: " + iriStr, e)
    }
  }

}

sealed abstract trait Value extends Product with Serializable 

sealed abstract class EntityId extends Value {
  def id: String
  def iri: IRI
}

case class PropertyId(
  id: String, 
  iri: IRI
  ) extends EntityId
object PropertyId {
  implicit val showPropertyId: Show[PropertyId] = Show.show(p => p.id.toString)
  implicit val orderingById: Ordering[PropertyId] = Ordering.by(_.id)
  def fromIRI(iri: IRI): PropertyId = {
    val (name, base) = Utils.splitIri(iri)
    PropertyId(name, iri)
  }
}

case class PropertyRecord(id: PropertyId, vertexId: VertexId)

sealed abstract class Entity extends Value {
  val vertexId: VertexId
  val entityId: EntityId
  val localStatements: List[LocalStatement]
  def withLocalStatement(prec: PropertyRecord, literal: LiteralValue, qs: List[Qualifier]): Entity
  def localStatementsByPropId(propId: PropertyId) = {
    localStatements.filter(_.propertyRecord.id == propId)
  }
}

case class ItemId(id: String, iri: IRI) extends EntityId

case class Item(
    itemId: ItemId, 
    vertexId: VertexId, 
    label: String, 
    siteIri: String = Value.siteDefault,
    localStatements: List[LocalStatement] 
    ) extends Entity {
    lazy val entityId = itemId  
    def iri: IRI = IRI(siteIri + "/" + itemId.id)
    override def toString = s"${itemId.id}(${itemId.iri.getLexicalForm})-$label@$vertexId"

    override def withLocalStatement(
      prec: PropertyRecord, 
      literal: LiteralValue, 
      qs: List[Qualifier] = List()): Item =
       this.copy(
         localStatements = this.localStatements :+ LocalStatement(prec,literal,qs)
        )

}

case class Property(
    propertyId: PropertyId, 
    vertexId: VertexId, 
    label: String, 
    siteIri: String = Value.siteDefault,
    localStatements: List[LocalStatement] 
    ) extends Entity {
    lazy val entityId = propertyId
    def iri: IRI = IRI(siteIri + "/" + propertyId.id)
    override def toString = s"${propertyId.id}-$label@$vertexId"

    lazy val prec: PropertyRecord = PropertyRecord(propertyId, vertexId)

    override def withLocalStatement(
      prec: PropertyRecord, 
      literal: LiteralValue, 
      qs: List[Qualifier] = List()): Property =
       this.copy(
         localStatements = this.localStatements :+ LocalStatement(prec,literal,qs)
        )    
}

sealed abstract class LiteralValue extends Value

case class StringValue(
    str: String
    ) extends LiteralValue {
    override def toString = s"$str"
  }

case class DateValue(
    date: String, 
    ) extends LiteralValue {
    override def toString = s"$date"
  }

case class Qualifier(propertyId: PropertyId, value: Entity) {
    override def toString = s"$propertyId:$value"
}

case class LocalQualifier(propertyId: PropertyId, value: LiteralValue) {
    override def toString = s"$propertyId:$value"
}

case class Statement(
  propertyRecord: PropertyRecord,
  qualifiers: List[Qualifier] = List(), 
) {

  def id: PropertyId = propertyRecord.id 

  def withQualifiers(qs: List[Qualifier]): Statement = 
     this.copy(qualifiers = qs)

  override def toString = s"$propertyRecord ${if (qualifiers.isEmpty) "" else s"{{" + qualifiers.map(_.toString).mkString(",") + "}}" }" 
} 

case class LocalStatement(
  propertyRecord: PropertyRecord,
  literal: LiteralValue,
  qualifiers: List[Qualifier]
) {

  def withQualifiers(qs: List[Qualifier]): LocalStatement = 
     this.copy(qualifiers = qs)

  override def toString = s"$propertyRecord - $literal${if (qualifiers.isEmpty) "" else s"{{" + qualifiers.map(_.toString).mkString(",") + "}}" }"   
}

object Statement {
    implicit val orderingById: Ordering[Statement] = Ordering.by(_.propertyRecord.id)
}


object Value {

  lazy val siteDefault = "http://www.wikidata.org/entity"

  def vertexEdges(
   triplets: List[(Entity, PropertyRecord, Entity, List[Qualifier])]
   ):(Seq[Vertex[Entity]], Seq[Edge[Statement]]) = {
    val subjects: Seq[Entity] = triplets.map(_._1)
    val objects: Seq[Entity] = triplets.map(_._3)
    val properties: Seq[PropertyRecord] = triplets.map(_._2)
    val qualProperties: Seq[PropertyId] = triplets.map(_._4.map(_.propertyId)).flatten
    val qualValues: Seq[Entity] = triplets.map(_._4.map(_.value)).flatten
    val values: Seq[Vertex[Entity]] = subjects.union(objects).union(qualValues).map(v => Vertex(v.vertexId,v))
    val edges = triplets.map(t => statement(t._1, t._2, t._3, t._4)).toSeq
    (values,edges)
  }

  def triple(
    subj: Entity, prop: PropertyRecord, value: Entity
    ): (Entity, PropertyRecord, Entity, List[Qualifier]) = {
    (subj, prop, value, List())
  }

  def tripleq(
    subj: Entity, prop: PropertyRecord, value: Entity, qs: List[Qualifier]
    ): (Entity, PropertyRecord, Entity, List[Qualifier]) = {
    (subj, prop, value, qs)
  }

  def Q(num: Int, label: String, site: String = siteDefault): Builder[Item] =  for {
      id <- getIdUpdate
    } yield {
      val qid = "Q" + num
      Item(ItemId(qid, iri = mkSite(site, qid)), id, label, site, List())
    }

  def mkSite(base: String, localName: String) = IRI(base + "/" + localName)

  def P(num: Int, label: String, site: String = siteDefault): Builder[Property] = for {
      id <- getIdUpdate
  } yield {
    val pid = "P" + num
    Property(PropertyId(pid, mkSite(site,pid)), id, label, site, List())
  }

  def Date(date: String): DateValue = 
    DateValue(date) 

  def Str(str: String): StringValue = 
    StringValue(str)

  def Pid(num: Int, site: String = siteDefault): PropertyId = {
    val pid = "P" + num
    PropertyId(pid, mkSite(site, pid))
  }

  def statement(
    subject: Entity,
    propertyRecord: PropertyRecord, 
    value: Entity, 
    qs: List[Qualifier]): Edge[Statement] = 
      Edge(subject.vertexId, value.vertexId, Statement(propertyRecord).withQualifiers(qs.toList))

    
}
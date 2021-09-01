package es.weso.wdsub.spark.wbmodel

import org.wikidata.wdtk.datamodel.helpers.JsonSerializer
import org.wikidata.wdtk.datamodel.implementation._
import org.wikidata.wdtk.datamodel.interfaces.{SiteLink => WBSiteLink, _}

import java.io.ByteArrayOutputStream
import scala.collection.JavaConverters._

object ValueWriter {

  def entity2JsonStr(v: Entity): String = {
    
    val os = new ByteArrayOutputStream()
//    val jsonSerializer = new JsonSerializer(os)
//    jsonSerializer.open()
    val str = entity2entityDocument(v) match {
      case id: ItemDocument => JsonSerializer.getJsonString(id)
      case pd: PropertyDocument => JsonSerializer.getJsonString(pd)
      case _ => ""
    }
    str
//    jsonSerializer.close()
  }

  def entity2entityDocument(v: Entity): EntityDocument = v match {
    case i: Item => {
      val ed = new ItemDocumentImpl(
        cnvItemId(i.itemId), 
        cnvMultilingual(i.labels).asJava,
        cnvMultilingual(i.descriptions).asJava,
        cnvMultilingual(i.aliases).asJava,
        cnvStatements(i.localStatements).asJava,
        cnvSiteLinks(i.siteLinks).asJava,
        0L
        )
      ed  
    }
    case p: Property => {
      val pd = new PropertyDocumentImpl(
        cnvPropertyId(p.propertyId),
        cnvMultilingual(p.labels).asJava,
        cnvMultilingual(p.descriptions).asJava,
        cnvMultilingual(p.aliases).asJava,
        cnvStatements(p.localStatements).asJava,
        cnvDatatype(p.datatype),
        0L
        )
      pd  
    }
  }

  def cnvMultilingual(m: Map[Lang,String]): List[MonolingualTextValue] = 
    m.toList.map { 
      case (lang,text) => 
        new MonolingualTextValueImpl(text, lang.code)
    }

  def cnvItemId(id: ItemId): ItemIdValue = 
    new ItemIdValueImpl(id.id,id.iri.getLexicalForm)

  def cnvPropertyId(pd: PropertyId): PropertyIdValue = 
    new PropertyIdValueImpl(pd.id, pd.iri.getLexicalForm)

  def cnvStatements(ls: List[LocalStatement]): List[StatementGroup] = List()

  def cnvSiteLinks(sl: List[SiteLink]): List[WBSiteLink] = List()

  def cnvDatatype(dt: Datatype): DatatypeIdValue = new DatatypeIdImpl(dt.name)

}
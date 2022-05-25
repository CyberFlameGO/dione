package com.paypal.dione.spark

import com.paypal.dione.spark.index.{IndexManagerUtils, IndexSpec}
import org.apache.spark.sql.catalyst.catalog.HiveTableRelation
import org.apache.spark.sql.catalyst.expressions.AttributeReference
import org.apache.spark.sql.catalyst.plans.logical.{Filter, LogicalPlan, Project}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.types.StructType

object DioneRule extends Rule[LogicalPlan] {

  override def apply(plan: LogicalPlan): LogicalPlan = {
    plan transform {

      // For a query covering index we switch between the original table and the index table
      case p @ Project(_, h @ HiveTableRelation(_, _, _))
        if getQualifiedIndex(h, p.projectList.map(_.name)).nonEmpty =>
        val idx = getQualifiedIndex(h, p.projectList.map(_.name)).get
        val indexCatalogTable = IndexManagerUtils.getSparkCatalogTable(Dione.getContext.spark,
          idx.indexTableName)
        val updatedAttributes = toAttributes(indexCatalogTable.dataSchema,
          h.dataCols.filter(dc => p.projectList.map(_.name).contains(dc.name)))
        p.copy(p.projectList,
          child = h.copy(tableMeta = indexCatalogTable, dataCols = updatedAttributes))

      case p @ Project(_, f @ Filter(_, h @ HiveTableRelation(_, _, _)))
        if getQualifiedIndex(h, p.references.map(_.name).toSeq).nonEmpty =>
        val idx = getQualifiedIndex(h, p.references.map(_.name).toSeq).get
        val indexCatalogTable = IndexManagerUtils.getSparkCatalogTable(Dione.getContext.spark,
          idx.indexTableName)
        val updatedAttributes = toAttributes(indexCatalogTable.dataSchema,
          h.dataCols.filter(dc => p.references.map(_.name).toSet.contains(dc.name)))
        p.copy(p.projectList,
          child = f.copy(f.condition,
            child = h.copy(tableMeta = indexCatalogTable, dataCols = updatedAttributes)))
    }
  }

  // based on StructType.toAttributes()
  def toAttributes(structType: StructType, origAttrs: Seq[AttributeReference]): Seq[AttributeReference] = {
    val origMap = origAttrs.map(ar => ar.name -> ar).toMap
    structType
      .map(f => origMap.getOrElse(f.name, AttributeReference(f.name, f.dataType, f.nullable, f.metadata)()))
  }

  def getQualifiedIndex(h: HiveTableRelation, referencedAtts: Seq[String]): Option[IndexSpec] = {
    Dione.getContext.indices.getOrElse(h.tableMeta.identifier.identifier, Nil)
      .find(ci => referencedAtts.forall(ci.getFields.contains))
  }
}

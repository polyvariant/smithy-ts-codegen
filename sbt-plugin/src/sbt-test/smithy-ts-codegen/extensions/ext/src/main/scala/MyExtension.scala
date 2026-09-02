package myorg

import org.polyvariant.smithy.ts.api.PathSegment
import org.polyvariant.smithy.ts.api.TsCodegenExtension
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape

/** Mounts each service under `/internal/<service version>` — a prefix the model does not describe,
  * derived from what it does.
  */
class MyExtension extends TsCodegenExtension {

  override def transformPath(
    service: ServiceShape,
    operation: OperationShape,
    path: List[PathSegment],
  ): List[PathSegment] =
    PathSegment.Literal("internal") ::
      PathSegment.Literal(service.getVersion) ::
      path

}

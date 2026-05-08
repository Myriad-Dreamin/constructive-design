package design

final case class SourceRange(start: Int, end: Int) {
  def contains(offset: Int): Boolean =
    start <= offset && offset < end

  def isEmpty: Boolean =
    start < 0 || end <= start
}

object SourceRange {
  val empty: SourceRange = SourceRange(-1, -1)

  def span(ranges: Iterable[SourceRange]): SourceRange = {
    val valid = ranges.filterNot(_.isEmpty).toList
    if valid.isEmpty then empty
    else SourceRange(valid.map(_.start).min, valid.map(_.end).max)
  }
}

package io.github.xiaomeng2568.meldwise.data

/** The UI order is explicit, bounded and independent of provider/model display labels. */
internal fun movedHistory(ids:List<String>,id:String,direction:Int):List<String> {
    require(direction in setOf(-1,1) && ids.distinct().size==ids.size)
    val index=ids.indexOf(id);require(index>=0)
    val target=index+direction
    if(target !in ids.indices) return ids
    return ids.toMutableList().apply {removeAt(index);add(target,id)}
}

/** Reorder only the matching slots; another mode's relative and absolute positions stay intact. */
internal fun movedCategoryHistory(ids:List<String>,members:Set<String>,id:String,direction:Int):List<String> {
    require(id in members && ids.containsAll(members) && ids.distinct().size==ids.size)
    val moved=movedHistory(ids.filter {it in members},id,direction).iterator()
    return ids.map {if(it in members) moved.next() else it}
}

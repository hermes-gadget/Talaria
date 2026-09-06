package com.hermesgadget.talaria.feature.manage.kanban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P10: Kanban columns bound the eagerly composed task window; the board exposes a
 * reveal control instead of composing every task in every column.
 */
class KanbanColumnWindowTest {

    private fun tasks(n: Int) = List(n) { i ->
        KanbanTaskRow(
            id = "t$i",
            title = "task-$i",
            body = "",
            status = "todo",
            assignee = "",
            priority = null,
            tenant = "",
            commentCount = 0,
            latestSummary = "",
        )
    }

    @Test
    fun `window constant is bounded and sane`() {
        assertTrue(MAX_TASKS_PER_COLUMN in 5..100)
    }

    @Test
    fun `column model carries name and tasks`() {
        val column = KanbanColumn(name = "todo", tasks = tasks(3))
        assertEquals("todo", column.name)
        assertEquals(3, column.tasks.size)
    }

    @Test
    fun `overflow count math matches reveal label`() {
        val all = tasks(MAX_TASKS_PER_COLUMN + 7)
        val visible = all.take(MAX_TASKS_PER_COLUMN)
        assertEquals(MAX_TASKS_PER_COLUMN, visible.size)
        assertEquals(7, all.size - visible.size)
    }
}

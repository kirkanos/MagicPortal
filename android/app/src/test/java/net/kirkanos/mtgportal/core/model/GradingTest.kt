package net.kirkanos.mtgportal.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GradingTest {
    @Test
    fun gradesAreHalfStepsFromTenDown() {
        assertEquals(19, Grading.GRADES.size)
        assertEquals(10.0, Grading.GRADES.first(), 0.0)
        assertEquals(1.0, Grading.GRADES.last(), 0.0)
    }

    @Test
    fun formatsLikeTheWeb() {
        assertEquals("10", Grading.format(10.0))
        assertEquals("9,5", Grading.format(9.5))
    }
}

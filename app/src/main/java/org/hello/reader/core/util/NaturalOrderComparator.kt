package org.hello.reader.core.util

import java.io.File

/**
 * Natural sort order comparator that sorts numbers numerically rather than lexicographically,
 * ensuring "Track 2.mp3" sorts before "Track 10.mp3", and "disc1/02.mp3" before "disc2/01.mp3".
 */
object NaturalOrderComparator : Comparator<String> {
    override fun compare(s1: String, s2: String): Int {
        var i1 = 0
        var i2 = 0
        while (i1 < s1.length && i2 < s2.length) {
            val c1 = s1[i1]
            val c2 = s2[i2]
            if (c1.isDigit() && c2.isDigit()) {
                var j1 = i1
                while (j1 < s1.length && s1[j1].isDigit()) j1++
                var j2 = i2
                while (j2 < s2.length && s2[j2].isDigit()) j2++

                val numStr1 = s1.substring(i1, j1)
                val numStr2 = s2.substring(i2, j2)
                val val1 = numStr1.toBigIntegerOrNull()
                val val2 = numStr2.toBigIntegerOrNull()
                val cmp = if (val1 != null && val2 != null) {
                    val1.compareTo(val2)
                } else {
                    numStr1.compareTo(numStr2)
                }
                if (cmp != 0) return cmp
                if (numStr1.length != numStr2.length) {
                    return numStr1.length.compareTo(numStr2.length)
                }
                i1 = j1
                i2 = j2
            } else {
                val cmp = c1.lowercaseChar().compareTo(c2.lowercaseChar())
                if (cmp != 0) return cmp
                i1++
                i2++
            }
        }
        return s1.length.compareTo(s2.length)
    }
}

val NaturalFileOrder: Comparator<File> = Comparator { f1, f2 ->
    val parentCmp = NaturalOrderComparator.compare(f1.parent ?: "", f2.parent ?: "")
    if (parentCmp != 0) parentCmp else NaturalOrderComparator.compare(f1.name, f2.name)
}

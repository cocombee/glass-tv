package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public class QuotesTest {
    @Test
    public void parsesTextAndSource() {
        List<String[]> q = Config.parseQuotes("# comment\n\nOne | Source A\r\nTwo\n  Three | B  \n");
        assertEquals(3, q.size());
        assertArrayEquals(new String[]{"One", "Source A"}, q.get(0));
        assertArrayEquals(new String[]{"Two", ""}, q.get(1));
        assertArrayEquals(new String[]{"Three", "B"}, q.get(2));
    }
}

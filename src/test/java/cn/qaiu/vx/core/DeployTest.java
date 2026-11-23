package cn.qaiu.vx.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DeployTest {

    @Test
    public void passwordIsMaskedInStartupLog() {
        assertEquals("******", Deploy.maskPassword("real-secret"));
        assertEquals("(none)", Deploy.maskPassword(null));
        assertEquals("(none)", Deploy.maskPassword(""));
    }
}

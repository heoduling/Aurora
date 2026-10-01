package gg.auroramc.aurora.lifecycle;

import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import static org.junit.jupiter.api.Assertions.*;

class InitializationTraceTest {
    @Test void nativeClassReferencesAreReleasedWithoutLosingTheInitializationDiagnostic() throws Exception {
        var diagnostic = new IllegalStateException("Initial initialization");
        var frames = diagnostic.getStackTrace();
        assertTrue(frames.length > 0);
        assertNotNull(ThreadLocalAccess.read(diagnostic, "backtrace"));
        ThreadLocalAccess.releaseBacktrace(diagnostic);
        assertNull(ThreadLocalAccess.read(diagnostic, "backtrace"));
        assertArrayEquals(frames, diagnostic.getStackTrace());
        var printed = new StringWriter();
        diagnostic.printStackTrace(new PrintWriter(printed));
        assertTrue(printed.toString().contains("Initial initialization"));
        assertTrue(printed.toString().contains("InitializationTraceTest"));
    }
}

package com.synopsys.integration.detect.battery.accuracy;

import org.junit.jupiter.api.Assertions;

import com.synopsys.integration.detect.workflow.report.output.FormattedDetectorOutput;
import com.synopsys.integration.detector.base.DetectorStatusCode;

public class FormattedDetectorAssertions {
    private final FormattedDetectorOutput detector;

    public FormattedDetectorAssertions(FormattedDetectorOutput detector) {this.detector = detector;}

    public void assertStatus(String status, String message) {
        Assertions.assertEquals(status, detector.status, message);
    }

    public void assertStatusCode(DetectorStatusCode statusCode, String message) {
        Assertions.assertEquals(statusCode, detector.statusCode, message);
    }
}

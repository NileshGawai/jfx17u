/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package test.javafx.scene.web;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import test.util.Util;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CSSRoundingTest {

    private static final CountDownLatch launchLatch = new CountDownLatch(1);

    // Maintain one application instance
    static CSSRoundingTestApp cssRoundingTestApp;
    public static Stage primaryStage;
    public static WebView webView;

    public static class CSSRoundingTestApp extends Application {

        @Override
        public void init() {
            CSSRoundingTest.cssRoundingTestApp = this;
        }

        @Override
        public void start(Stage primaryStage) throws Exception {
            CSSRoundingTest.primaryStage = primaryStage;
            webView = new WebView();
            Scene scene = new Scene(webView, 150, 100);
            primaryStage.setScene(scene);
            primaryStage.show();
            launchLatch.countDown();
        }
    }

    @BeforeAll
    public static void setupOnce() {
        Util.launch(launchLatch, CSSRoundingTestApp.class);
    }

    @AfterAll
    public static void tearDownOnce() {
        Util.shutdown();
    }

    @Test
    public void testCSSroundingForLinearLayout() {

        final CountDownLatch webViewStateLatch = new CountDownLatch(1);

        Util.runAndWait(() -> {
            assertNotNull(webView);

            webView.getEngine().getLoadWorker().stateProperty().addListener((observable, oldValue, newValue) -> {
                if (newValue == Worker.State.SUCCEEDED) {
                    webView.requestFocus();
                }
            });

            webView.focusedProperty().addListener((observable, oldValue, newValue) -> {
                if (newValue) {
                    webViewStateLatch.countDown();
                }
            });

            String content = "<html>\n" +
                    "<head>\n" +
                    "<style type=\"text/css\">\n" +
                    "    body, div {\n" +
                    "        margin: 0;\n" +
                    "        padding: 0;\n" +
                    "        border: 0;\n" +
                    "    }\n" +
                    "    #top, #bottom {\n" +
                    "        line-height: 1.5;\n" +
                    "        font-size: 70%;\n" +
                    "        background:green;\n" +
                    "        color:white;\n" +
                    "        width:100%;\n" +
                    "    }\n" +
                    "    #top {\n" +
                    "        padding:.6em 0 .7em;\n" +
                    "    }\n" +
                    "    #bottom {\n" +
                    "      position:absolute;\n" +
                    "      top:2.8em;\n" +
                    "    }\n" +
                    "</style>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "<div id=\"top\">no gap below</div>\n" +
                    "<div id=\"bottom\">no gap above</div>\n" +
                    "<div id=\"description\"></div>\n" +
                    "<div id=\"console\"></div>\n" +
                    "<script>\n" +
                    "description(\"This test checks that floating point rounding doesn't cause misalignment.  There should be no gap between the divs.\");\n" +
                    "var divtop = document.getElementById(\"top\").getBoundingClientRect();\n" +
                    "var divbottom = document.getElementById(\"bottom\").getBoundingClientRect();\n" +
                    "console.log(\"divtop.bottom: \" + divtop.bottom);\n" +
                    "console.log(\"divbottom.top: \" + divbottom.top);\n" +
                    "window.testResults = { topBottom: Math.round(divtop.bottom), bottomTop: Math.round(divbottom.top) };\n" +
                    "</script>\n" +
                    "</body>\n" +
                    "</html>\n";
            webView.getEngine().loadContent(content);
        });

        assertTrue(Util.await(webViewStateLatch), "Timeout when waiting for focus change");
        // Introduce sleep to ensure web contents are loaded
        Util.sleep(1000);

        Util.runAndWait(() -> {
            webView.getEngine().executeScript(
                    "var divtop = document.getElementById(\"top\").getBoundingClientRect();\n" +
                    "var divbottom = document.getElementById(\"bottom\").getBoundingClientRect();\n" +
                    "var topBottom = Math.round(divtop.bottom);\n" +
                    "var bottomTop = Math.round(divbottom.top);\n" +
                    "window.testResults = { topBottom: topBottom, bottomTop: bottomTop };\n");

            int topBottom = ((Number) webView.getEngine().executeScript("window.testResults.topBottom")).intValue();
            int bottomTop = ((Number) webView.getEngine().executeScript("window.testResults.bottomTop")).intValue();

            assertEquals(31, topBottom);
            assertEquals(31, bottomTop);
        });
    }
}

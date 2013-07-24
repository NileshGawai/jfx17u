/*
 * Copyright (c) 2009, 2013, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.prism.impl;

import com.sun.javafx.font.CharToGlyphMapper;
import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.FontStrike;
import com.sun.javafx.font.Glyph;
import com.sun.javafx.geom.BaseBounds;
import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.Point2D;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.scene.text.GlyphList;
import com.sun.prism.impl.packrect.RectanglePacker;
import com.sun.prism.Texture;
import com.sun.prism.impl.shape.MaskData;
import com.sun.prism.paint.Color;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.WeakHashMap;

import static com.sun.javafx.logging.PulseLogger.PULSE_LOGGING_ENABLED;
import static com.sun.javafx.logging.PulseLogger.PULSE_LOGGER;
import com.sun.prism.ResourceFactory;
import com.sun.prism.Texture.WrapMode;

public class GlyphCache {

    // REMIND: For a less powerful device, the size of this cache
    // is likely something we'd want to tune as they may have much less
    // VRAM and are less likely to be used for apps that have huge
    // text demands.
    // 2048 pixels introduced very noticeable pauses when trying
    // to free 1/4 of the glyphs, which for spiral text also amounts
    // to 1/4 of the strikes.
    private static final int WIDTH = 1024; // in pixels
    private static final int HEIGHT = 1024; // in pixels
    private static ByteBuffer emptyMask;

    private final BaseContext context;
    private final FontStrike strike;

    // segmented arrays are in blocks of 32 glyphs.
    private static final int SEGSHIFT = 5;
    private static final int SEGSIZE  = 1 << SEGSHIFT;
    HashMap<Integer, GlyphData[]>
        glyphDataMap = new HashMap<Integer, GlyphData[]>();

    // Because of SEGSHIFT the 5 high bit in the key to glyphDataMap are unused
    // Using them for subpixel
    private static final int SUBPIXEL_NONE = 0;
    private static final int SUBPIXEL_ONEQUARTER    = 0x08000000; // bit 27
    private static final int SUBPIXEL_ONEHALF       = 0x10000000; // bit 28
    private static final int SUBPIXEL_THREEQUARTERS = 0x18000000; // bit 27+28

    private RectanglePacker packer;

    private boolean isLCDCache;

    /* Share a RectanglePacker and its associated texture cache
     * for all uses on a particular screen.
     */
    static WeakHashMap<BaseContext, RectanglePacker> greyPackerMap =
        new WeakHashMap<BaseContext, RectanglePacker>();

    static WeakHashMap<BaseContext, RectanglePacker> lcdPackerMap =
        new WeakHashMap<BaseContext, RectanglePacker>();

    public GlyphCache(BaseContext context, FontStrike strike) {
        this.context = context;
        this.strike = strike;
        //numGlyphs = strike.getNumGlyphs();
        //int numSegments = (numGlyphs + SEGSIZE-1)/SEGSIZE;
        //this.glyphs = new GlyphData[numSegments][];
        isLCDCache = strike.getAAMode() == FontResource.AA_LCD;
        WeakHashMap<BaseContext, RectanglePacker>
            packerMap = isLCDCache ? lcdPackerMap : greyPackerMap;
        packer = packerMap.get(context);
        if (packer == null) {
            ResourceFactory factory = context.getResourceFactory();
            Texture tex = factory.createMaskTexture(WIDTH, HEIGHT,
                                                    WrapMode.CLAMP_NOT_NEEDED);
            tex.contentsUseful();
            tex.makePermanent();
            tex.setLinearFiltering(false);
            packer = new RectanglePacker(tex, WIDTH, HEIGHT);
            packerMap.put(context, packer);
        }
    }

    public void render(BaseContext ctx, GlyphList gl, float x, float y,
                       int start, int end, Color rangeColor, Color textColor,
                       BaseTransform xform, BaseBounds clip) {

        int dstw, dsth;
        if (isLCDCache) {
            dstw = ctx.getLCDBuffer().getPhysicalWidth();
            dsth = ctx.getLCDBuffer().getPhysicalHeight();
        } else {
            dstw = 1;
            dsth = 1;
        }
        Texture tex = getBackingStore();
        VertexBuffer vb = ctx.getVertexBuffer();

        int len = gl.getGlyphCount();
        Color currentColor = null;
        Point2D pt = new Point2D();

        boolean subPixel = strike.isSubPixelGlyph();
        float subPixelX = 0;
        for (int gi = 0; gi < len; gi++) {
            int gc = gl.getGlyphCode(gi);

            // If we have a supplementary character, then a special
            // glyph is inserted in the list, which is one we skip
            // over for rendering. It has no advance.
            if (gc == CharToGlyphMapper.INVISIBLE_GLYPH_ID) {
                continue;
            }
            pt.setLocation(x + gl.getPosX(gi), y + gl.getPosY(gi));
            if (subPixel) {
                subPixelX = pt.x;
                pt.x = (int)pt.x;
                subPixelX -= pt.x;
            }
            GlyphData data = getCachedGlyph(gl, gi, subPixelX, 0);
            if (data != null) {
                if (clip != null) {
                    // Always check clipping using user space.
                    if (x + gl.getPosX(gi) > clip.getMaxX()) break;
                    if (x + gl.getPosX(gi + 1) < clip.getMinX()) {
                        pt.x += data.getXAdvance();
                        pt.y += data.getYAdvance();
                        continue;
                    }
                }
                /* Will not render selected text for complex
                 * paints such as gradient.
                 */
                if (rangeColor != null && textColor != null) {
                    int offset = gl.getCharOffset(gi);
                    if (start <= offset && offset < end) {
                        if (rangeColor != currentColor) {
                            vb.setPerVertexColor(rangeColor, 1.0f);
                            currentColor = rangeColor;
                        }
                    } else {
                        if (textColor != currentColor) {
                            vb.setPerVertexColor(textColor, 1.0f);
                            currentColor = textColor;
                        }
                    }
                }
                xform.transform(pt, pt);
                addDataToQuad(data, vb, tex, pt.x, pt.y, dstw, dsth);
            }
        }
    }

    private void addDataToQuad(GlyphData data, VertexBuffer vb,
                               Texture tex, float x, float y,
                               float dstw, float dsth) {
        // We are sampling texture using nearest point sampling, for clear
        // text. As a consequence of nearest point sampling, graphics artifacts
        // may occur when sampling close to texel boundaries.
        // By rounding the glyph placement we can avoid the texture boundaries.
        // REMIND: If we start using linear sampling then we should remove
        // rounding.
        y = Math.round(y);
        Rectangle rect = data.getRect();
        if (rect == null) {
            // Glyph with no visual representation (whitespace)
            return;
        }
        int border = data.getBlankBoundary();
        float gw = rect.width - (border * 2);
        float gh = rect.height - (border * 2);
        float dx1 = data.getOriginX() + x;
        float dy1 = data.getOriginY() + y;
        float dx2;
        float dy2 = dy1 + gh;
        float tw = tex.getPhysicalWidth();
        float th = tex.getPhysicalHeight();
        float tx1 = (rect.x + border) / tw;
        float ty1 = (rect.y + border) / th;
        float tx2 = tx1 + (gw / tw);
        float ty2 = ty1 + (gh / th);
        if (isLCDCache) {
            dx1 = Math.round(dx1 * 3.0f) / 3.0f;
            dx2 = dx1 + gw / 3.0f;
            float t2x1 = dx1 / dstw;
            float t2x2 = dx2 / dstw;
            float t2y1 = dy1 / dsth;
            float t2y2 = dy2 / dsth;
            vb.addQuad(dx1, dy1, dx2, dy2, tx1, ty1, tx2, ty2, t2x1, t2y1, t2x2, t2y2);
        } else {
            dx1 = Math.round(dx1);
            dx2 = dx1 + gw;
            vb.addQuad(dx1, dy1, dx2, dy2, tx1, ty1, tx2, ty2);
        }
    }

    public Texture getBackingStore() {
        return packer.getBackingStore();
    }

    public void clear() {
        glyphDataMap.clear();
    }

    private void clearAll() {
        // flush any pending vertices that may depend on the current state
        // of the glyph cache texture.
        context.flushVertexBuffer();
        context.clearGlyphCaches();
        packer.clear();
    }

    private GlyphData getCachedGlyph(GlyphList gl, int gi, float x, float y) {
        int glyphCode = gl.getGlyphCode(gi);
        int segIndex = glyphCode >> SEGSHIFT;
        int subIndex = glyphCode % SEGSIZE;
        if (x != 0) {
            if (x < 0.25) {
                x = 0;
            } else if (x < 0.50) {
                x = 0.25f;
                segIndex |= SUBPIXEL_ONEQUARTER;
            } else if (x < 0.75) {
                x = 0.50f;
                segIndex |= SUBPIXEL_ONEHALF;
            } else {
                x = 0.75f;
                segIndex |= SUBPIXEL_THREEQUARTERS;
            }
        }
        GlyphData[] segment = glyphDataMap.get(segIndex);
        if (segment != null) {
            if (segment[subIndex] != null) {
                return segment[subIndex];
            }
        } else {
            segment = new GlyphData[SEGSIZE];
            glyphDataMap.put(segIndex, segment);
        }

        // Render the glyph and insert it in the cache
        GlyphData data = null;
        Glyph glyph = strike.getGlyph(gl, gi);
        if (glyph != null) {
            if (glyph.getWidth() == 0 || glyph.getHeight() == 0) {
                data = new GlyphData(0, 0, 0,
                                     glyph.getPixelXAdvance(),
                                     glyph.getPixelYAdvance(),
                                     null);
            } else {
                // Rasterize the glyph
                // TODO: needs more work for fractional metrics support (RT-27423)
                // NOTE : if the MaskData can be stored back directly
                // in the glyph, even as an opaque type, it should save
                // repeated work next time the glyph is used.
                MaskData maskData = MaskData.create(glyph.getPixelData(x ,y),
                                                    glyph.getOriginX(),
                                                    glyph.getOriginY(),
                                                    glyph.getWidth(),
                                                    glyph.getHeight());

                // Make room for the rectangle on the backing store
                int border = 1;
                int rectW = maskData.getWidth()  + (2 * border);
                int rectH = maskData.getHeight() + (2 * border);
                int originX = maskData.getOriginX();
                int originY = maskData.getOriginY();
                Rectangle rect = new Rectangle(0, 0, rectW, rectH);
                data = new GlyphData(originX, originY, border,
                                     glyph.getPixelXAdvance(),
                                     glyph.getPixelYAdvance(),
                                     rect);

                if (!packer.add(rect)) {
                    if (PULSE_LOGGING_ENABLED) PULSE_LOGGER.renderIncrementCounter("Font Glyph Cache Cleared");
                    // If add fails,clear up the cache. Try add again.
                    clearAll();
                    packer.add(rect);
                }

                // We always pass skipFlush=true to backingStore.update()
                // since we are in control of the contents of the backingStore
                // texture and explicitly flush the vertex buffer only when
                // it is truly needed.
                boolean skipFlush = true;

                // Upload the an empty byte array to ensure the boundary
                // area is filled with zeros. Note that the rectangle
                // is already padded on each edge.
                Texture backingStore = getBackingStore();
                int emw = rect.width;
                int emh = rect.height;
                int bpp = backingStore.getPixelFormat().getBytesPerPixelUnit();
                int stride = emw * bpp;
                int size = stride * emh;
                if (emptyMask == null || size > emptyMask.capacity()) {
                    emptyMask = BufferUtil.newByteBuffer(size);
                }
                // try/catch is a precaution against not fitting into the store.
                try {
                    backingStore.update(emptyMask,
                                        backingStore.getPixelFormat(),
                                        rect.x, rect.y,
                                        0, 0, emw, emh, stride,
                                        skipFlush);
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
                // Upload the glyph
                maskData.uploadToTexture(backingStore,
                                         border + rect.x,
                                         border + rect.y,
                                         skipFlush);

            }
            segment[subIndex] = data;
        }

        return data;
    }

    static class GlyphData {
        // The following must be defined and used VERY precisely. This is
        // the offset from the upper-left corner of this rectangle (Java
        // 2D coordinate system) at which the string must be rasterized in
        // order to fit within the rectangle -- the leftmost point of the
        // baseline.
        private final int originX;
        private final int originY;

        // The blank boundary around the real image of the glyph on
        // the backing store
        private final int blankBoundary;

        // The advance of this glyph
        private final float xAdvance, yAdvance;

        // The rectangle on the backing store corresponding to this glyph
        private final Rectangle rect;

        GlyphData(int originX, int originY, int blankBoundary,
                  float xAdvance, float yAdvance, Rectangle rect)
        {
            this.originX = originX;
            this.originY = originY;
            this.blankBoundary = blankBoundary;
            this.xAdvance = xAdvance;
            this.yAdvance = yAdvance;
            this.rect = rect;
        }

        int getOriginX() {
            return originX;
        }

        int getOriginY() {
            return originY;
        }

        int getBlankBoundary() {
            return blankBoundary;
        }

        float getXAdvance() {
            return xAdvance;
        }

        float getYAdvance() {
            return yAdvance;
        }

        Rectangle getRect() {
            return rect;
        }
    }
}
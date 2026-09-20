package com.rpgloader;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.rpgloader.butterscotch.GameRunner;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

public class GameActivity extends Activity
        implements TextureView.SurfaceTextureListener {

    private TextureView textureView;
    private GameRunner runner;
    private String dataWinPath;

    private FrameLayout root;
    private GameControls controls;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        dataWinPath = getIntent().getStringExtra("dataWinPath");

        if (dataWinPath == null) {
            finish();
            return;
        }

        root = new FrameLayout(this);

        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(this);
        textureView.setOpaque(true);

        root.addView(
                textureView,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );

        controls = new GameControls();

        root.addView(
                controls,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );

        setContentView(root);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    public void onSurfaceTextureAvailable(
            SurfaceTexture surfaceTexture,
            int width,
            int height
    ) {
        if (runner != null) return;

        Surface surface = new Surface(surfaceTexture);

        java.io.File saves = new java.io.File(
                getExternalFilesDir(null),
                "saves/" + Integer.toHexString(dataWinPath.hashCode())
        );

        if (!saves.exists()) {
            saves.mkdirs();
        }

        runner = new GameRunner(
                dataWinPath,
                saves.getAbsolutePath(),
                0
        );

        runner.start(surface);
    }

    @Override
    public void onSurfaceTextureSizeChanged(
            SurfaceTexture surface,
            int width,
            int height
    ) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        stopRunner();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    private void stopRunner() {
        GameRunner r = runner;
        runner = null;

        if (r != null) {
            r.stop();
        }
    }

    @Override
    protected void onDestroy() {
        stopRunner();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        stopRunner();
        finish();
    }

    private void sendDown(int key) {
        GameRunner r = runner;
        if (r != null) {
            r.keyDown(key);
        }
    }

    private void sendUp(int key) {
        GameRunner r = runner;
        if (r != null) {
            r.keyUp(key);
        }
    }

    private class GameControls extends View {

        /*
         * GameMaker keyboard codes.
         */
        private static final int KEY_Z = 90;
        private static final int KEY_X = 88;
        private static final int KEY_C = 67;

        private static final int KEY_UP = 38; // VK_UP
        private static final int KEY_DOWN = 40; // VK_DOWN
        private static final int KEY_LEFT = 37; // VK_LEFT
        private static final int KEY_RIGHT = 39; // VK_RIGHT

        private final Paint paint = new Paint();
        private final Paint bitmapPaint = new Paint();

        private Bitmap sprites;

        /*
         * Sprite-sheet source rectangles.
         *
         * Each sprite is 16x16.
         */
        private final Rect srcZ = new Rect(0, 0, 16, 16);
        private final Rect srcZPressed = new Rect(0, 16, 16, 32);

        private final Rect srcX = new Rect(0, 32, 16, 48);
        private final Rect srcXPressed = new Rect(0, 48, 16, 64);

        private final Rect srcC = new Rect(0, 64, 16, 80);
        private final Rect srcCPressed = new Rect(0, 80, 16, 96);

        private final Rect srcJoystick = new Rect(0, 96, 16, 112);
        private final Rect srcThumb = new Rect(0, 112, 16, 128);

        private final RectF zRect = new RectF();
        private final RectF xRect = new RectF();
        private final RectF cRect = new RectF();
        private final RectF joystickRect = new RectF();

        private float thumbX;
        private float thumbY;

        private boolean joystickActive = false;
        private int joystickPointerId = -1;

        /*
         * Keys currently held by the whole control system.
         */
        private final Set<Integer> heldKeys = new HashSet<>();

        /*
         * Keys currently controlled by the joystick.
         */
        private final Set<Integer> joystickKeys = new HashSet<>();

        /*
         * Buttons are tracked by pointer ID.
         */
        private final java.util.HashMap<Integer, Integer> pointerButtons =
                new java.util.HashMap<>();

        GameControls() {
            super(GameActivity.this);

            setFocusable(false);

            /*
             * VERY IMPORTANT for pixel-art sprites:
             * no bilinear filtering.
             */
            bitmapPaint.setAntiAlias(false);
            bitmapPaint.setFilterBitmap(false);
            bitmapPaint.setDither(false);

            loadSprites();
        }

        private void loadSprites() {
            try {
                InputStream in =
                        getAssets().open("controls.png");

                sprites = BitmapFactory.decodeStream(in);

                in.close();

                if (sprites == null) {
                    throw new RuntimeException(
                            "controls.png could not be decoded"
                    );
                }

            } catch (Exception e) {
                android.util.Log.e(
                        "RPGLoader",
                        "Failed to load controls.png",
                        e
                );
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (sprites == null) return;

            float w = getWidth();
            float h = getHeight();

            updateControlGeometry();

            /*
             * -------------------------------------------------
             * BUTTONS
             * -------------------------------------------------
             *
             * Slightly larger than before.
             * Shifted left.
             * Smaller gaps.
             *
             *                 C
             *             X
             *         Z
             */

            drawButton(
                    canvas,
                    zRect,
                    srcZ,
                    srcZPressed,
                    KEY_Z
            );

            drawButton(
                    canvas,
                    xRect,
                    srcX,
                    srcXPressed,
                    KEY_X
            );

            drawButton(
                    canvas,
                    cRect,
                    srcC,
                    srcCPressed,
                    KEY_C
            );

            /*
             * -------------------------------------------------
             * JOYSTICK
             * -------------------------------------------------
             *
             * Moved slightly right.
             * Bigger than before.
             */

            if (!joystickActive) {
                thumbX = joystickRect.centerX();
                thumbY = joystickRect.centerY();
            }

            canvas.drawBitmap(
                    sprites,
                    srcJoystick,
                    joystickRect,
                    bitmapPaint
            );

            /*
             * Thumb is 8x8 inside a 16x16 sprite.
             * We use the whole 16x16 cell because the
             * transparent area is part of the sprite.
             */
            float thumbSize = 105f;

            RectF thumbRect = new RectF(
                    thumbX - thumbSize / 2f,
                    thumbY - thumbSize / 2f,
                    thumbX + thumbSize / 2f,
                    thumbY + thumbSize / 2f
            );

            canvas.drawBitmap(
                    sprites,
                    srcThumb,
                    thumbRect,
                    bitmapPaint
            );
        }

        private void drawButton(
                Canvas canvas,
                RectF dst,
                Rect normal,
                Rect pressed,
                int key
        ) {
            Rect source =
                    heldKeys.contains(key)
                            ? pressed
                            : normal;

            canvas.drawBitmap(
                    sprites,
                    source,
                    dst,
                    bitmapPaint
            );
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {

            final int action = event.getActionMasked();

            switch (action) {

                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int index = event.getActionIndex();
                    int pointerId = event.getPointerId(index);

                    processPointer(
                            pointerId,
                            event.getX(index),
                            event.getY(index)
                    );

                    return true;
                }

                case MotionEvent.ACTION_MOVE: {

                    /*
                     * Every pointer gets updated.
                     */
                    for (int i = 0;
                         i < event.getPointerCount();
                         i++) {

                        int pointerId =
                                event.getPointerId(i);

                        processPointer(
                                pointerId,
                                event.getX(i),
                                event.getY(i)
                        );
                    }

                    return true;
                }

                case MotionEvent.ACTION_POINTER_UP: {

                    int index = event.getActionIndex();
                    int pointerId = event.getPointerId(index);

                    releasePointer(pointerId);

                    /*
                     * Update remaining pointers.
                     */
                    for (int i = 0;
                         i < event.getPointerCount();
                         i++) {

                        if (i == index) continue;

                        int id = event.getPointerId(i);

                        processPointer(
                                id,
                                event.getX(i),
                                event.getY(i)
                        );
                    }

                    invalidate();
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:

                    releaseEverything();
                    return true;
            }

            return true;
        }

        private void updateControlGeometry() {
            float w = getWidth();
            float h = getHeight();

            if (w <= 0 || h <= 0) return;

            /*
             * Must match onDraw().
             */
            float joystickSize = 205f;
            float joyX = 155f;
            float joyY = h - joystickSize - 55f;

            joystickRect.set(
                    joyX,
                    joyY,
                    joyX + joystickSize,
                    joyY + joystickSize
            );

            float buttonSize = 88f;
            float buttonGap = 4f;
            float rightMargin = 48f;

            float zX =
                    w - rightMargin
                            - buttonSize * 3f
                            - buttonGap * 2f
                            - 20f;

            float zY = h - 92f;

            float xX = zX + buttonSize + buttonGap;
            float xY = zY - buttonSize - buttonGap;

            float cX = xX + buttonSize + buttonGap;
            float cY = xY - buttonSize - buttonGap;

            zRect.set(
                    zX,
                    zY,
                    zX + buttonSize,
                    zY + buttonSize
            );

            xRect.set(
                    xX,
                    xY,
                    xX + buttonSize,
                    xY + buttonSize
            );

            cRect.set(
                    cX,
                    cY,
                    cX + buttonSize,
                    cY + buttonSize
            );

            if (!joystickActive) {
                thumbX = joystickRect.centerX();
                thumbY = joystickRect.centerY();
            }
        }

        private void processPointer(
                int pointerId,
                float x,
                float y
        ) {
            updateControlGeometry();


            /*
             * Make sure joystickRect has valid coordinates
             * even if touch arrives before the first draw.
             */
            updateControlGeometry();

            /*
             * JOYSTICK
             *
             * Generous touch area around the sprite.
             */
            float touchExpand = 55f;

            RectF joystickTouch = new RectF(
                    joystickRect.left - touchExpand,
                    joystickRect.top - touchExpand,
                    joystickRect.right + touchExpand,
                    joystickRect.bottom + touchExpand
            );

            if (joystickPointerId == pointerId ||
                    (joystickPointerId == -1 &&
                            joystickTouch.contains(x, y))) {

                if (joystickPointerId == -1) {
                    joystickPointerId = pointerId;

                    android.util.Log.d(
                            "RPGLoaderControls",
                            "JOYSTICK DOWN pointer=" + pointerId +
                            " x=" + x + " y=" + y
                    );
                }

                updateJoystick(x, y);
            }

            /*
             * Buttons.
             *
             * Their touch area is slightly larger than
             * their visual sprite.
             */
            float buttonExpand = 12f;

            RectF r = new RectF();

            r.set(
                    zRect.left - buttonExpand,
                    zRect.top - buttonExpand,
                    zRect.right + buttonExpand,
                    zRect.bottom + buttonExpand
            );

            if (r.contains(x, y)) {
                pointerButtons.put(pointerId, KEY_Z);
            }

            r.set(
                    xRect.left - buttonExpand,
                    xRect.top - buttonExpand,
                    xRect.right + buttonExpand,
                    xRect.bottom + buttonExpand
            );

            if (r.contains(x, y)) {
                pointerButtons.put(pointerId, KEY_X);
            }

            r.set(
                    cRect.left - buttonExpand,
                    cRect.top - buttonExpand,
                    cRect.right + buttonExpand,
                    cRect.bottom + buttonExpand
            );

            if (r.contains(x, y)) {
                pointerButtons.put(pointerId, KEY_C);
            }

            rebuildButtonKeys();

            invalidate();
        }

        private void updateJoystick(float x, float y) {

            float cx = joystickRect.centerX();
            float cy = joystickRect.centerY();

            float dx = x - cx;
            float dy = y - cy;

            float radius =
                    joystickRect.width() * 0.39f;

            float distance =
                    (float) Math.sqrt(
                            dx * dx +
                            dy * dy
                    );

            if (distance > radius && distance > 0f) {
                dx =
                        dx / distance *
                                radius;

                dy =
                        dy / distance *
                                radius;
            }

            thumbX = cx + dx;
            thumbY = cy + dy;

            Set<Integer> newKeys =
                    new HashSet<>();

            /*
             * Deadzone.
             */
            float deadzone = 20f;

            if (Math.abs(dx) > deadzone) {
                if (dx < 0) {
                    newKeys.add(KEY_LEFT);
                } else {
                    newKeys.add(KEY_RIGHT);
                }
            }

            if (Math.abs(dy) > deadzone) {
                if (dy < 0) {
                    newKeys.add(KEY_UP);
                } else {
                    newKeys.add(KEY_DOWN);
                }
            }

            /*
             * Only update the joystick state here.
             *
             * rebuildButtonKeys() is the single place that
             * sends actual key DOWN/UP events.
             */
            joystickKeys.clear();
            joystickKeys.addAll(newKeys);

            rebuildButtonKeys();

            android.util.Log.d(
                    "RPGLoaderControls",
                    "JOYSTICK keys=" + newKeys
            );

            joystickActive = true;

            invalidate();
        }

        private void rebuildButtonKeys() {

            Set<Integer> newKeys =
                    new HashSet<>();

            newKeys.addAll(joystickKeys);

            for (Integer key :
                    pointerButtons.values()) {

                newKeys.add(key);
            }

            /*
             * Keys that disappeared.
             */
            for (Integer key :
                    new HashSet<>(heldKeys)) {

                if (!newKeys.contains(key)) {
                    sendUp(key);
                    heldKeys.remove(key);
                }
            }

            /*
             * Newly pressed keys.
             */
            for (Integer key : newKeys) {

                if (!heldKeys.contains(key)) {
                    sendDown(key);
                    heldKeys.add(key);
                }
            }
        }

        private void releasePointer(int pointerId) {

            pointerButtons.remove(pointerId);

            if (joystickPointerId == pointerId) {

                joystickKeys.clear();

                joystickPointerId = -1;
                joystickActive = false;

                thumbX = joystickRect.centerX();
                thumbY = joystickRect.centerY();
            }

            rebuildButtonKeys();
            invalidate();
        }

        private void releaseEverything() {

            for (Integer key :
                    new HashSet<>(heldKeys)) {

                sendUp(key);
            }

            heldKeys.clear();
            joystickKeys.clear();
            pointerButtons.clear();

            joystickPointerId = -1;
            joystickActive = false;

            thumbX = joystickRect.centerX();
            thumbY = joystickRect.centerY();

            invalidate();
        }
    }
}

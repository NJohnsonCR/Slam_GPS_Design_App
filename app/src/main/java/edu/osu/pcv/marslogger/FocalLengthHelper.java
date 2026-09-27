package edu.osu.pcv.marslogger;

// estimate focal length, i.e., imaging distance in pixels, using all sorts of info

import android.annotation.TargetApi;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.util.SizeF;

import timber.log.Timber;

public class FocalLengthHelper {

    private static final String TAG = "FocalLengthHelper";

    private float[] mIntrinsic;
    private Float mFocalLength;
    private Float mFocusDistance;
    private SizeF mPhysicalSize;
    private Size mPixelArraySize;
    private Rect mPreCorrectionSize; // This rectangle is defined relative to full pixel array; (0,0) is the top-left of the full pixel array
    private Rect mActiveSize; // This rectangle is defined relative to the full pixel array; (0,0) is the top-left of the full pixel array,
    private Rect mCropRegion; // Its The coordinate system is defined relative to the active array rectangle given in this field, with (0, 0) being the top-left of this rectangle.
    private Size mImageSize;

    public FocalLengthHelper() {

    }

    public void setLensParams(CameraCharacteristics result) {
        setLensParams21(result);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            setLensParams23(result);
        }
    }

    public void setmCropRegion(Rect mCropRegion) {
        this.mCropRegion = mCropRegion;
    }

    public void setmFocalLength(Float mFocalLength) {
        this.mFocalLength = mFocalLength;
    }

    public void setmFocusDistance(Float mFocusDistance) {
        this.mFocusDistance = mFocusDistance;
    }

    public void setmImageSize(Size mImageSize) {
        this.mImageSize = mImageSize;
    }

    /**
     * compute the focal length in pixels.
     * First it tries to use values read from LENS_INTRINSIC_CALIBRATION, if not available,
     * it will compute focal length based on an empirical model.
     *
     * Both LENS_INTRINSIC_CALIBRATION and the empirical model are in active array pixels,
     * so both are scaled to the output image with imageScale().
     *
     * focus distance is the inverse of the distance between the lens and the subject,
     * assuming LENS_INFO_FOCUS_DISTANCE_CALIBRATION is APPROXIMATE or CALIBRATED.
     * see https://stackoverflow.com/questions/60394282/unit-of-camera2-lens-focus-distance
     * i is the distance between the imaging sensor and the lens.
     * Recall 1/focal_length = focus_distance + 1/i.
     * Because focal_length is very small say 3 mm,
     * focus_distance is often comparatively small, say 5 1/meter,
     * i is often very close to the physical focal length, say 3 mm.
     *
     * see: https://source.android.com/devices/camera/camera3_crop_reprocess.html
     * https://stackoverflow.com/questions/39965408/what-is-the-android-camera2-api-equivalent-of-camera-parameters-gethorizontalvie
     *
     * @return (focal length along x, focal length along y) in pixels
     */
    public SizeF getFocalLengthPixel() {
        if (mIntrinsic != null && mIntrinsic[0] > 1.0) {
            float scale = imageScale();
            return new SizeF(scale * mIntrinsic[0], scale * mIntrinsic[1]);
        }

        if (mFocalLength != null) {
            Float imageDistance; // mm
            if (mFocusDistance == null || mFocusDistance == 0.f) {
                imageDistance = mFocalLength;
            } else {
                imageDistance = 1000.f / (1000.f / mFocalLength - mFocusDistance);
            }
            // ignore the effect of distortion on the active array coordinates
            float scale = imageScale();
            Float f_image_pixel;
            if (isWidthLimited()) {
                f_image_pixel = scale * imageDistance * mPixelArraySize.getWidth() /
                        mPhysicalSize.getWidth();
            } else {
                f_image_pixel = scale * imageDistance * mPixelArraySize.getHeight() /
                        mPhysicalSize.getHeight();
            }
            return new SizeF(f_image_pixel, f_image_pixel);
        }
        return new SizeF(1.0f, 1.0f);
    }

    /**
     * Principal point (cx, cy) in output image pixels, or the image center if the device
     * does not report LENS_INTRINSIC_CALIBRATION.
     */
    public PointF getPrincipalPoint() {
        float width = mImageSize.getWidth();
        float height = mImageSize.getHeight();
        if (mIntrinsic == null || mIntrinsic[0] <= 1.0 || mCropRegion == null) {
            return new PointF(width / 2, height / 2);
        }
        // The calibration is relative to the pre-correction array, the crop region to the
        // active array.
        float cx = mIntrinsic[2];
        float cy = mIntrinsic[3];
        if (mPreCorrectionSize != null && mActiveSize != null) {
            cx += mPreCorrectionSize.left - mActiveSize.left;
            cy += mPreCorrectionSize.top - mActiveSize.top;
        }
        // The output image is centered in the crop region.
        float scale = imageScale();
        float left = mCropRegion.exactCenterX() - width / (2 * scale);
        float top = mCropRegion.exactCenterY() - height / (2 * scale);
        return new PointF(scale * (cx - left), scale * (cy - top));
    }

    /** True if the output image spans the full width of the crop region. */
    private boolean isWidthLimited() {
        float cropAspect = (float) mCropRegion.width() / mCropRegion.height();
        float imageAspect = (float) mImageSize.getWidth() / mImageSize.getHeight();
        return imageAspect >= cropAspect;
    }

    /**
     * Converts active array pixels to output image pixels.
     * Assumes CONTROL_ZOOM_RATIO is 1, which holds because this app never sets it.
     */
    private float imageScale() {
        if (isWidthLimited()) {
            return (float) mImageSize.getWidth() / mCropRegion.width();
        }
        return (float) mImageSize.getHeight() / mCropRegion.height();
    }

    @TargetApi(23)
    private void setLensParams23(CameraCharacteristics result) {
        mIntrinsic = result.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
        if (mIntrinsic != null)
            Timber.d("char lens intrinsics fx %f fy %f cx %f cy %f s %f",
                    mIntrinsic[0], mIntrinsic[1], mIntrinsic[2], mIntrinsic[3], mIntrinsic[4]);
        mPreCorrectionSize =
                result.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
        if (mPreCorrectionSize != null)
            Timber.d("Precorrection rect %s", mPreCorrectionSize.toString());
    }

    private void setLensParams21(CameraCharacteristics result) {
        mPhysicalSize = result.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (mPhysicalSize != null)
            Timber.d("Physical size %s", mPhysicalSize.toString());
        mPixelArraySize = result.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if (mPixelArraySize != null)
            Timber.d("Pixel array size %s", mPixelArraySize.toString());
        mActiveSize = result.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (mActiveSize != null)
            Timber.d("Active rect %s", mActiveSize.toString());
    }
}

/*
 * Copyright (C) 2013 Peng fei Pan <sky@xiaopan.me>
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.xiaopan.sketch.decode;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.support.annotation.NonNull;

import java.util.LinkedList;
import java.util.List;

import me.xiaopan.sketch.Identifier;
import me.xiaopan.sketch.SLog;
import me.xiaopan.sketch.datasource.DataSource;
import me.xiaopan.sketch.request.ErrorCause;
import me.xiaopan.sketch.request.LoadRequest;
import me.xiaopan.sketch.uri.GetDataSourceException;
import me.xiaopan.sketch.util.ExifInterface;

/**
 * 图片解码器，读取bitmap之前执行计算采样比例、选择合适的config、读取方向、寻找可复用的bitmap等操作，之后进行方向纠正、处理、缓存等操作
 */
public class ImageDecoder implements Identifier {
    private static final String NAME = "ImageDecoder";

    private DecodeTimeAnalyze timeAnalyze = new DecodeTimeAnalyze();
    private List<DecodeHelper> decodeHelperList;
    private List<ResultProcessor> resultProcessorList;

    public ImageDecoder() {
        decodeHelperList = new LinkedList<>();
        resultProcessorList = new LinkedList<>();

        decodeHelperList.add(new ProcessedCacheDecodeHelper());
        decodeHelperList.add(new GifDecodeHelper());
        decodeHelperList.add(new ThumbnailModeDecodeHelper());
        decodeHelperList.add(new NormalDecodeHelper());

        resultProcessorList.add(new ProcessImageResultProcessor());
        resultProcessorList.add(new ProcessedResultCacheProcessor());
    }

    /**
     * 解码入口方法，统计解码时间、调用解码方法以及后续处理
     *
     * @param request LoadRequest
     * @return DecodeResult
     * @throws DecodeException 解码失败了
     */
    @NonNull
    public DecodeResult decode(@NonNull LoadRequest request) throws DecodeException {
        DecodeResult result = null;
        try {
            long startTime = 0;
            if (SLog.isLoggable(SLog.LEVEL_DEBUG | SLog.TYPE_TIME)) {
                startTime = timeAnalyze.decodeStart();
            }
            result = doDecode(request);
            if (SLog.isLoggable(SLog.LEVEL_DEBUG | SLog.TYPE_TIME)) {
                timeAnalyze.decodeEnd(startTime, NAME, request.getKey());
            }

            try {
                doProcess(request, result);
            } catch (ProcessException e) {
                result.recycle(request.getConfiguration().getBitmapPool());
                throw new DecodeException(e, ErrorCause.DECODE_PROCESS_IMAGE_FAIL);
            }

            return result;
        } catch (DecodeException e) {
            if (result != null) {
                result.recycle(request.getConfiguration().getBitmapPool());
            }
            throw e;
        } catch (Throwable tr) {
            if (result != null) {
                result.recycle(request.getConfiguration().getBitmapPool());
            }
            throw new DecodeException(tr, ErrorCause.DECODE_UNKNOWN_EXCEPTION);
        }
    }

    /**
     * 执行具体解码，这个方法里值读取出解码所需的一些属性，然后再交给具体的DecodeHelper去解码
     *
     * @param request LoadRequest
     * @return DecodeResult
     * @throws DecodeException 解码失败了
     */
    @NonNull
    private DecodeResult doDecode(LoadRequest request) throws DecodeException {
        DataSource dataSource;
        try {
            dataSource = request.getDataSourceWithPressedCache();
        } catch (GetDataSourceException e) {
            ImageDecodeUtils.decodeError(request, null, NAME, "Unable create DataSource", e);
            throw new DecodeException("Unable create DataSource", e, ErrorCause.DECODE_UNABLE_CREATE_DATA_SOURCE);
        }

        // Decode bounds and mime info
        BitmapFactory.Options boundOptions = new BitmapFactory.Options();
        boundOptions.inJustDecodeBounds = true;
        try {
            ImageDecodeUtils.decodeBitmap(dataSource, boundOptions);
        } catch (Throwable e) {
            ImageDecodeUtils.decodeError(request, dataSource, NAME, "Unable read bound information", e);
            throw new DecodeException("Unable read bound information", e, ErrorCause.DECODE_UNABLE_READ_BOUND_INFORMATION);
        }

        // Exclude images with a width of less than or equal to 1
        if (boundOptions.outWidth <= 1 || boundOptions.outHeight <= 1) {
            String cause = String.format("Image width or height less than or equal to 1px. imageSize: %dx%d", boundOptions.outWidth, boundOptions.outHeight);
            ImageDecodeUtils.decodeError(request, dataSource, NAME, cause, null);
            throw new DecodeException(cause, ErrorCause.DECODE_BOUND_RESULT_IMAGE_SIZE_INVALID);
        }

        // Read image orientation
        int exifOrientation = ExifInterface.ORIENTATION_UNDEFINED;
        if (!request.getOptions().isCorrectImageOrientationDisabled()) {
            ImageOrientationCorrector imageOrientationCorrector = request.getConfiguration().getOrientationCorrector();
            exifOrientation = imageOrientationCorrector.readExifOrientation(boundOptions.outMimeType, dataSource);
        }

        ImageType imageType = ImageType.valueOfMimeType(boundOptions.outMimeType);

        // Set whether priority is given to quality or speed
        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD_MR1
                && request.getOptions().isInPreferQualityOverSpeed()) {
            decodeOptions.inPreferQualityOverSpeed = true;
        }

        // Setup preferred bitmap config
        Bitmap.Config newConfig = request.getOptions().getBitmapConfig();
        if (newConfig == null && imageType != null) {
            newConfig = imageType.getConfig(request.getOptions().isLowQualityImage());
        }
        if (newConfig != null) {
            decodeOptions.inPreferredConfig = newConfig;
        }

        DecodeResult decodeResult = null;
        for (DecodeHelper decodeHelper : decodeHelperList) {
            if (decodeHelper.match(request, dataSource, imageType, boundOptions)) {
                decodeResult = decodeHelper.decode(request, dataSource, imageType, boundOptions, decodeOptions, exifOrientation);
                break;
            }
        }

        if (decodeResult != null) {
            decodeResult.setImageFrom(dataSource.getImageFrom());
            return decodeResult;
        } else {
            ImageDecodeUtils.decodeError(request, null, NAME, "No matching DecodeHelper", null);
            throw new DecodeException("No matching DecodeHelper", ErrorCause.DECODE_NO_MATCHING_DECODE_HELPER);
        }
    }

    /**
     * 执行后续的处理，包括转换、缓存
     *
     * @param request LoadRequest
     * @param result  DecodeResult
     * @throws ProcessException 处理失败了
     */
    private void doProcess(LoadRequest request, DecodeResult result) throws ProcessException {
        if (result == null || result.isBanProcess()) {
            return;
        }

        for (ResultProcessor resultProcessor : resultProcessorList) {
            resultProcessor.process(request, result);
        }
    }

    @NonNull
    @Override
    public String getKey() {
        return NAME;
    }
}

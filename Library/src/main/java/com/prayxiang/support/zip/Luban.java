package com.prayxiang.support.zip;

import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.support.annotation.WorkerThread;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@SuppressWarnings("unused")
public class Luban implements Handler.Callback {
  private static final String TAG = "Luban";
  private static final String DEFAULT_DISK_CACHE_DIR = "luban_disk_cache";

  private static final int MSG_COMPRESS_SUCCESS = 0;
  private static final int MSG_COMPRESS_START = 1;
  private static final int MSG_COMPRESS_ERROR = 2;

  private String mTargetDir;
  private int mLeastCompressSize;
  private OnCompressListener mCompressListener;
  private CompressionPredicate mCompressionPredicate;
  private List<InputStreamProvider> mStreamProviders;

  private Handler mHandler;

  private Luban(Builder builder) {
    this.mTargetDir = builder.mTargetDir;
    this.mStreamProviders = builder.mStreamProviders;
    this.mCompressListener = builder.mCompressListener;
    this.mLeastCompressSize = builder.mLeastCompressSize;
    this.mCompressionPredicate = builder.mCompressionPredicate;
    mHandler = new Handler(Looper.getMainLooper(), this);
  }

  public static Builder with(Context context) {
    return new Builder(context);
  }

  /**
   * Returns a mFile with a cache audio name in the private cache directory.
   *
   * @param context A context.
   */
  private File getImageCacheFile(Context context, String suffix) {
    if (TextUtils.isEmpty(mTargetDir)) {
      mTargetDir = getImageCacheDir(context).getAbsolutePath();
    }

    String cacheBuilder = mTargetDir + "/" +
        System.currentTimeMillis() +
        (int) (Math.random() * 1000) +
        (TextUtils.isEmpty(suffix) ? ".jpg" : suffix);

    return new File(cacheBuilder);
  }

  /**
   * Returns a directory with a default name in the private cache directory of the application to
   * use to store retrieved audio.
   *
   * @param context A context.
   * @see #getImageCacheDir(Context, String)
   */
  @Nullable
  private File getImageCacheDir(Context context) {
    return getImageCacheDir(context, DEFAULT_DISK_CACHE_DIR);
  }

  /**
   * Returns a directory with the given name in the private cache directory of the application to
   * use to store retrieved media and thumbnails.
   *
   * @param context   A context.
   * @param cacheName The name of the subdirectory in which to store the cache.
   * @see #getImageCacheDir(Context)
   */
  @Nullable
  private static File getImageCacheDir(Context context, String cacheName) {
    File cacheDir = context.getExternalCacheDir();
    if (cacheDir != null) {
      File result = new File(cacheDir, cacheName);
      if (!result.mkdirs() && (!result.exists() || !result.isDirectory())) {
        // File wasn't able to create a directory, or the result exists but not a directory
        return null;
      }
      return result;
    }
    if (Log.isLoggable(TAG, Log.ERROR)) {
      Log.e(TAG, "default disk cache dir is null");
    }
    return null;
  }

  /**
   * start asynchronous compress thread
   */
  @UiThread
  private void launch(final Context context) {
    if (mStreamProviders == null || mStreamProviders.size() == 0 && mCompressListener != null) {
      mCompressListener.onError(new NullPointerException("image file cannot be null"));
    }

    Iterator<InputStreamProvider> iterator = mStreamProviders.iterator();

    while (iterator.hasNext()) {
      final InputStreamProvider path = iterator.next();

      AsyncTask.SERIAL_EXECUTOR.execute(new Runnable() {
        @Override
        public void run() {
          try {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_COMPRESS_START));

            File result;
            if (mCompressionPredicate != null) {
              if (mCompressionPredicate.apply(path.getPath())) {
                result = Checker.SINGLE.isNeedCompress(mLeastCompressSize, path.getPath()) ?
                    new Engine(path, getImageCacheFile(context, Checker.SINGLE.extSuffix(path.getPath()))).compress() :
                    new File(path.getPath());
              } else {
                result = new File(path.getPath());
              }
            } else {
              result = Checker.SINGLE.isNeedCompress(mLeastCompressSize, path.getPath()) ?
                  new Engine(path, getImageCacheFile(context, Checker.SINGLE.extSuffix(path.getPath()))).compress() :
                  new File(path.getPath());
            }

            mHandler.sendMessage(mHandler.obtainMessage(MSG_COMPRESS_SUCCESS, result));
          } catch (IOException e) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_COMPRESS_ERROR, e));
          }
        }
      });

      iterator.remove();
    }
  }

  /**
   * start compress and return the mFile
   */
  @WorkerThread
  private File get(InputStreamProvider path, Context context) throws IOException {
    return new Engine(path, getImageCacheFile(context, Checker.SINGLE.extSuffix(path.getPath()))).compress();
  }

  @WorkerThread
  private List<File> get(Context context) throws IOException {
    List<File> results = new ArrayList<>();
    Iterator<InputStreamProvider> iterator = mStreamProviders.iterator();

    while (iterator.hasNext()) {
      InputStreamProvider path = iterator.next();

      if (mCompressionPredicate != null) {
        results.add(mCompressionPredicate.apply(path.getPath()) ?
            new Engine(path, getImageCacheFile(context, Checker.SINGLE.extSuffix(path.getPath()))).compress() :
            new File(path.getPath()));
      } else {
        results.add(new Engine(path, getImageCacheFile(context, Checker.SINGLE.extSuffix(path.getPath()))).compress());
      }

      iterator.remove();
    }

    return results;
  }

  @Override
  public boolean handleMessage(Message msg) {
    if (mCompressListener == null) return false;

    switch (msg.what) {
      case MSG_COMPRESS_START:
        mCompressListener.onStart();
        break;
      case MSG_COMPRESS_SUCCESS:
        mCompressListener.onSuccess((File) msg.obj);
        break;
      case MSG_COMPRESS_ERROR:
        mCompressListener.onError((Throwable) msg.obj);
        break;
    }
    return false;
  }

  public static class Builder {
    private Context context;
    private String mTargetDir;
    private List<InputStreamProvider> mStreamProviders;
    private int mLeastCompressSize = 100;
    private OnCompressListener mCompressListener;
    private CompressionPredicate mCompressionPredicate;

    Builder(Context context) {
      this.context = context;
      this.mStreamProviders = new ArrayList<>();
    }

    private Luban build() {
      return new Luban(this);
    }

    public Builder load(InputStreamProvider inputStreamProvider) {
      mStreamProviders.add(inputStreamProvider);
      return this;
    }

    public Builder load(final File file) {
      mStreamProviders.add(new InputStreamProvider() {
        @Override
        public InputStream open() throws IOException {
          return new FileInputStream(file);
        }

        @Override
        public String getPath() {
          return file.getAbsolutePath();
        }
      });
      return this;
    }

    public Builder load(final String string) {
      mStreamProviders.add(new InputStreamProvider() {
        @Override
        public InputStream open() throws IOException {
          return new FileInputStream(string);
        }

        @Override
        public String getPath() {
          return string;
        }
      });
      return this;
    }

    public Builder load(List<String> list) {
      for (String s : list) {
        load(s);
      }
      return this;
    }

    public Builder load(final Uri uri) {
      mStreamProviders.add(new InputStreamProvider() {
        @Override
        public InputStream open() throws IOException {
          return context.getContentResolver().openInputStream(uri);
        }

        @Override
        public String getPath() {
          return uri.getPath();
        }
      });
      return this;
    }

    public Builder putGear(int gear) {
      return this;
    }

    public Builder setCompressListener(OnCompressListener listener) {
      this.mCompressListener = listener;
      return this;
    }

    public Builder setTargetDir(String targetDir) {
      this.mTargetDir = targetDir;
      return this;
    }

    /**
     * do not compress when the origin image file size less than one value
     *
     * @param size the value of file size, unit KB, default 100K
     */
    public Builder ignoreBy(int size) {
      this.mLeastCompressSize = size;
      return this;
    }

    /**
     * do compress image when return value was true, otherwise, do not compress the image file
     *
     * @param compressionPredicate A predicate callback that returns true or false for the given input path should be compressed.
     */
    public Builder filter(CompressionPredicate compressionPredicate) {
      this.mCompressionPredicate = compressionPredicate;
      return this;
    }


    /**
     * begin compress image with asynchronous
     */
    public void launch() {
      build().launch(context);
    }

    public File get(final String path) throws IOException {
      return build().get(new InputStreamProvider() {
        @Override
        public InputStream open() throws IOException {
          return new FileInputStream(path);
        }

        @Override
        public String getPath() {
          return path;
        }
      }, context);
    }

    /**
     * begin compress image with synchronize
     *
     * @return the thumb image file list
     */
    public List<File> get() throws IOException {
      return build().get(context);
    }
  }
}
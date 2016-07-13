package com.zshc.frank.imageloader.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图片加载
 * 单例模式
 * Created by Administrator on 2016/7/12 0012.
 */
public class ImageLoader {
    private static final int DEFAULT_THREAD_COUNT = 1;
    private static ImageLoader mInstance;
    /**
     * 图片缓存的核心对象
     */
    private LruCache<String, Bitmap> mLruCache;
    //线程池
    private ExecutorService mThreadPool;
    /**
     * 队列的调度方式
     */
    private Type mType = Type.LIFO;
    /**
     * 任务队列
     */
    private LinkedList<Runnable> mTaskQueue;

    /**
     * 后台轮询线程
     */
    private Thread mPoolThread;
    private Handler mPoolThreadHandler;

    /**
     * UI线程中的Handler
     */
    private Handler mUIHandler;

    private ImageLoader(int threadCount, Type type) {
        init(threadCount, type);
    }

    public static ImageLoader getmInstance() {
        if (mInstance == null) {
            synchronized (ImageLoader.class) {
                if (mInstance == null) {
                    mInstance = new ImageLoader(DEFAULT_THREAD_COUNT, Type.LIFO);
                }
            }
        }
        return mInstance;
    }

    /**
     * 根据path为imageView设置图片V
     *
     * @param path
     * @param imageView
     */
    public void loadImage(final String path, final ImageView imageView) {
        imageView.setTag(path);

        if (mUIHandler == null) {
            mUIHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    //获取图片，为imageView回调设置图片
                    ImgBeanHolder holder = (ImgBeanHolder) msg.obj;
                    Bitmap bm = holder.bitmap;
                    ImageView imageView = holder.imageView;
                    String path = holder.path;
                    //将path与getTag存储路径进行比较
                    if (imageView.getTag().toString().equals(path)) {
                        imageView.setImageBitmap(bm);
                    }
                }
            };
        }
        Bitmap bm = getBitmapFromLruCache(path);

        if (bm != null) {
            Message message = Message.obtain();
            ImgBeanHolder holder = new ImgBeanHolder();
            holder.bitmap = bm;
            holder.path = path;
            holder.imageView = imageView;
            message.obj = holder;
            mUIHandler.sendMessage(message);
        } else {
            addTasks(new Runnable() {
                @Override
                public void run() {
                    //加载图片
                    //图片压缩
                    //1.获得图片需要显示的大小
                    ImageSize imageSize = getImageViewSize(imageView);
                    //2.压缩图片
                    Bitmap bm = decodeSampleBitmapFormPath(path,imageSize.width,imageSize.height);
                }
            });
        }
    }

    protected Bitmap decodeSampleBitmapFormPath(String path,int width,int height){
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path,options);

        options.inSampleSize = caculateInSampleSize(options,width,height);

        return null;
    }

    /**
     * 根据需求的宽和高以及实际的宽和高计算SampleSize
     * @param options
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    private int caculateInSampleSize(BitmapFactory.Options options,int reqWidth,int reqHeight){
        int width = options.outWidth;
        int height = options.outHeight;

        int inSampleSize = 1;
        if (width > reqWidth || height > reqHeight){
            int widthRadio = Math.round(width*1.0f/reqWidth);
            int heightRadio = Math.round(height*1.0f/reqHeight);

            inSampleSize = Math.max(widthRadio,heightRadio);

        }
        return inSampleSize;
    }

    /**
     * 根据ImageView获取适当压缩的宽和高
     *
     * @param imageView
     * @return
     */
    protected ImageSize getImageViewSize(ImageView imageView) {
        ImageSize imageSize = new ImageSize();

        DisplayMetrics displayMetrics = imageView.getContext().getResources().getDisplayMetrics();

        ViewGroup.LayoutParams lp = imageView.getLayoutParams();

        int width = imageView.getWidth();//获取imageView的实际宽度
        if (width <= 0) {
            width = lp.width; //获取imageView在layout中声明的宽度
        }
        if (width <= 0) {
            width = imageView.getMaxWidth();//检查最大值
        }
        if (width <= 0) {
            width = displayMetrics.widthPixels;
        }

        int height = imageView.getHeight();//获取imageView的实际高度
        if (height <= 0) {
            height = lp.height; //获取imageView在layout中声明的高度
        }
        if (height <= 0) {
            height = imageView.getMaxHeight();//检查最大值
        }
        if (height <= 0) {
            height = displayMetrics.heightPixels;
        }

        imageSize.width = width;
        imageSize.height = height;
        return imageSize;
    }

    private void addTasks(Runnable runnable) {

        mTaskQueue.add(runnable);
        mPoolThreadHandler.sendEmptyMessage(0x110);
    }

    private void init(int threadCount, Type type) {
        //后台轮询线程
        mPoolThread = new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                mPoolThreadHandler = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        //线程池取一个任务执行

                        mThreadPool.execute(getTask());
                    }
                };
                Looper.loop();
            }
        };
        mPoolThread.start();

        //获取我们应用的最大可用内存
        int maxMemory = (int) Runtime.getRuntime().maxMemory();
        int cacheMemory = maxMemory / 8;
        mLruCache = new LruCache<String, Bitmap>(cacheMemory) {
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight();
            }
        };

        //创建线程池
        mThreadPool = Executors.newFixedThreadPool(threadCount);
        mTaskQueue = new LinkedList<Runnable>();
        mType = type;
    }

    public Runnable getTask() {
        if (mType == Type.FIFO) {
            return mTaskQueue.removeFirst();
        } else if (mType == Type.LIFO) {
            return mTaskQueue.removeLast();
        }
        return null;
    }

    private Bitmap getBitmapFromLruCache(String key) {
        return mLruCache.get(key);
    }

    public enum Type {
        FIFO, LIFO;
    }

    private class ImageSize {
        int width;
        int height;
    }

    private class ImgBeanHolder {
        Bitmap bitmap;
        ImageView imageView;
        String path;
    }
}
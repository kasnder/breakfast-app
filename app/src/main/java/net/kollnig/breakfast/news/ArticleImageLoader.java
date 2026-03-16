package net.kollnig.breakfast.news;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Lazily loads article images into rows with a small in-memory bitmap cache.
 */
public final class ArticleImageLoader {
    private static final ArticleImageLoader INSTANCE = new ArticleImageLoader();

    private final LruCache<String, Bitmap> memoryCache;
    private final ExecutorService executor;
    private final OkHttpClient client;

    private ArticleImageLoader() {
        int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        int cacheSizeKb = Math.max(2048, maxMemoryKb / 16);
        memoryCache = new LruCache<String, Bitmap>(cacheSizeKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        executor = Executors.newFixedThreadPool(2);
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    public static ArticleImageLoader getInstance() {
        return INSTANCE;
    }

    public void load(@Nullable String imageUrl, ImageView imageView) {
        imageView.setTag(imageUrl);

        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            imageView.setImageDrawable(null);
            imageView.setVisibility(View.GONE);
            return;
        }

        Bitmap cached = memoryCache.get(imageUrl);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            imageView.setVisibility(View.VISIBLE);
            return;
        }

        imageView.setImageDrawable(null);
        imageView.setVisibility(View.GONE);

        executor.execute(() -> {
            Bitmap bitmap = fetchBitmap(imageUrl);
            if (bitmap == null) {
                return;
            }

            memoryCache.put(imageUrl, bitmap);
            imageView.post(() -> {
                Object currentTag = imageView.getTag();
                if (!imageUrl.equals(currentTag)) {
                    return;
                }
                imageView.setImageBitmap(bitmap);
                imageView.setVisibility(View.VISIBLE);
            });
        });
    }

    @Nullable
    private Bitmap fetchBitmap(String imageUrl) {
        Request request = new Request.Builder()
                .url(imageUrl)
                .header("User-Agent", "Breakfast/1.0")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }

            try (InputStream stream = response.body().byteStream()) {
                return BitmapFactory.decodeStream(stream);
            }
        } catch (Exception ignored) {
            return null;
        }
    }
}

package net.mikaelzero.mojito.view.sketch;

import android.net.Uri;
import android.view.View;

import net.mikaelzero.mojito.interfaces.ImageViewLoadFactory;
import net.mikaelzero.mojito.loader.ContentLoader;
import net.mikaelzero.mojito.loader.PhotoViewContentLoader;

import org.jetbrains.annotations.NotNull;


public class SketchImageLoadFactory implements ImageViewLoadFactory {
    @Override
    public void loadSillContent(@NotNull View view, @NotNull Uri uri) {
        if (view.getTag() instanceof PhotoViewContentLoader) {
            ((PhotoViewContentLoader) view.getTag()).loadUri(uri);
        }
    }

    @Override
    public void loadContentFail(@NotNull View view, int drawableResId) {
        if (view.getTag() instanceof PhotoViewContentLoader) {
            ((PhotoViewContentLoader) view.getTag()).loadFail(drawableResId);
        }
    }

    @NotNull
    @Override
    public ContentLoader newContentLoader() {
        return new PhotoViewContentLoader();
    }
}

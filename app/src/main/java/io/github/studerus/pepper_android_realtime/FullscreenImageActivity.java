package io.github.studerus.pepper_android_realtime;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;

public class FullscreenImageActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ImageView iv = new ImageView(this);
        iv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setBackgroundColor(Color.BLACK);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        setContentView(iv);

        String path = getIntent().getStringExtra("image_path");
        if (path == null) path = getIntent().getStringExtra("imagePath");

        if (path != null && new File(path).exists()) {
            Bitmap bmp = BitmapFactory.decodeFile(path);
            iv.setImageBitmap(bmp);
        } else {
            Toast.makeText(this, R.string.error_image_load_failed, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        iv.setOnClickListener(v -> finish());
    }
}

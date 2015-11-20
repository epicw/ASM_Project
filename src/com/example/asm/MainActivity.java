package com.example.asm;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import android.graphics.Bitmap;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.ShutterCallback;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.TextView;

public class MainActivity extends Activity implements PreviewCallback {
	private final String TAG = "com.example.asm.MainActivity";
	private final int BUFFER_SIZE = 4096;
	private final long DOUBLE_PRESS_INTERVAL = 500;
	public int countFrame = 0;

	static {
		System.loadLibrary("opencv_java");
	}

	private CameraPreview mPreview;
	private Camera mCamera;
	SurfaceView surfaceView;
	//Preview surface handle for callback
	SurfaceHolder surfaceHolder;
	
	private ImageView iv_canny;
	private ImageView iv_face_detect_img_view;
	private ImageView iv_image_view_asm;
	private TextView tv_info;
	private Button take_picture;
	boolean previewing;
	int mCurrentCamIndex = 0;
	
	private Mat currentFrame = new Mat();

	public Handler mHandler;
	private FaceDetectThread faceDetectThread;
	private boolean faceFound = false;
	private Bitmap asmBitmap;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		Log.d(TAG, "on Create");

		copyDataFile2LocalDir();

		tv_info = (TextView) findViewById(R.id.text_view_info);
		iv_canny = (ImageView) findViewById(R.id.image_view_canny);
		iv_face_detect_img_view = (ImageView) findViewById(R.id.face_detect_img_view);
		iv_image_view_asm = (ImageView) findViewById(R.id.image_view_asm);
		take_picture = (Button) findViewById(R.id.btn_do_asm);

		mHandler = new Handler() {

			@Override
			public void handleMessage(Message msg) {
				if (msg.what == Params.FACE_DETECT_DONE) {
					Mat detected = (Mat) msg.obj;
					Bitmap face_detected_bitmap = ImageUtils
							.mat2Bitmap(detected);
					iv_face_detect_img_view
							.setImageBitmap(face_detected_bitmap);
				}
			}
		};

		initUI();
	}

	private void initUI() {
		take_picture.setOnClickListener(new ClickEvent());
		iv_canny.setOnClickListener(new ClickEvent());
		iv_image_view_asm.setOnClickListener(new ClickEvent());
		iv_face_detect_img_view.setOnClickListener(new ClickEvent());
	}

	private class ClickEvent implements View.OnClickListener {

		long last = 0;
		long current = 0;

		@Override
		public void onClick(View v) {
			last = current;
			current = System.currentTimeMillis();
			boolean doubleClick = false;

			if (current - last < DOUBLE_PRESS_INTERVAL) {
				// legal double click
				Log.i(TAG, "double click");
				doubleClick = true;
			}


			if (v == iv_face_detect_img_view && doubleClick) {
				// start FaceDetectActivity
				Intent intent = new Intent(MainActivity.this,
						FaceDetectActivity.class);
				startActivity(intent);
			}

			if (v == iv_image_view_asm && doubleClick) {
				// start AsmViewActivity
				if (faceFound == false) {
					Toast.makeText(MainActivity.this, "no available ASM image",
							Toast.LENGTH_SHORT).show();
				} else {
					// store image on device
					saveBitmap(asmBitmap);
					Intent intent = new Intent();
					intent.setAction(Intent.ACTION_VIEW);
					intent.setDataAndType(Uri.parse("file://"
							+ Params.ASMActivity.LOCAL_FILE), "image/*");
					startActivity(intent);
				}
			}

			if (v == take_picture) {
				mCamera.takePicture(shutterCallback, rawPictureCallback, jpegPictureCallback);
			}
		}
	}
	
	ShutterCallback shutterCallback = new ShutterCallback() {
		@Override
		public void onShutter() {
		}
	};	
	
	PictureCallback rawPictureCallback = new PictureCallback() {
		@Override
		public void onPictureTaken(byte[] arg0, Camera arg1) {

		}
	};
	
	PictureCallback jpegPictureCallback = new PictureCallback() {
		@Override
		public void onPictureTaken(byte[] arg0, Camera arg1) {

			String fileName = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
					.toString()
					+ File.separator
					+ "PicTest_" + System.currentTimeMillis() + ".jpg";
			File file = new File(fileName);
			if (!file.getParentFile().exists()) {
				file.getParentFile().mkdir();
			}
			
			try {
				BufferedOutputStream bos = new BufferedOutputStream(
						new FileOutputStream(file));
				bos.write(arg0);
				bos.flush();
				bos.close();		
				scanFileToPhotoAlbum(file.getAbsolutePath());
				Toast.makeText(MainActivity.this, "[Test] Photo take and store in" + file.toString(),Toast.LENGTH_LONG).show();
			} catch (Exception e) {
				Toast.makeText(MainActivity.this, "Picture Failed" + e.toString(),
						Toast.LENGTH_LONG).show();
			}
			
			mCamera.stopPreview();
			mCamera.startPreview();
		};
	};
	
	public void scanFileToPhotoAlbum(String path) {

        MediaScannerConnection.scanFile(MainActivity.this,
                new String[] { path }, null,
                new MediaScannerConnection.OnScanCompletedListener() {

                    public void onScanCompleted(String path, Uri uri) {
                        Log.i("TAG", "Finished scanning " + path);
                    }
                });
    }
	
	private void saveBitmap(Bitmap bitmap) {
		String dir_path = Environment.getExternalStorageDirectory()
				.getAbsolutePath();
		File dir = new File(dir_path);
		if (!dir.exists()) {
			dir.mkdirs();
		}

		try {
			File file = new File(Params.ASMActivity.LOCAL_FILE);
			if (file.exists()) {
				file.delete();
			}

			FileOutputStream out = new FileOutputStream(file);
			bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
			out.flush();
			out.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	protected void onResume() {
		Log.d(TAG, "on resume");
		super.onResume();

		faceDetectThread = new FaceDetectThread(this);
		faceDetectThread.start();

		// Create an instance of Camera
		mCamera = CameraUtils.getCameraInstance(this,
				Camera.CameraInfo.CAMERA_FACING_FRONT);
		
		// Create our Preview view and set it as the content of our activity.
		mPreview = new CameraPreview(this, mCamera);
		FrameLayout previewFrame = (FrameLayout) findViewById(R.id.camera_preview);

		FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT);

		layoutParams.gravity = Gravity.CENTER;
		previewFrame.addView(mPreview, layoutParams);
		/*surfaceView = (SurfaceView) findViewById(R.id.camera_preview);
		surfaceHolder = surfaceView.getHolder();
		surfaceHolder.addCallback(new SurfaceViewCallback());
		//surfaceHolder.addCallback(this);
		surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);*/
	}

	@Override
	protected void onPause() {
		super.onPause();
		Log.d(TAG, "on pause");

		try {
			faceDetectThread.interrupt();
		} catch (Exception e) {
			Log.i(TAG, e.toString());
		}

		if (mPreview != null) {
			FrameLayout preview = (FrameLayout) findViewById(R.id.camera_preview);
			preview.removeView(mPreview);
			mPreview = null;
		}
	}

	@Override
	protected void onStop() {
		super.onPause();

		Log.d(TAG, "on stop");
	}

	@Override
	protected void onDestroy() {
		Log.d(TAG, "on Destroy");
		super.onDestroy();
	}

	@Override
	public void onPreviewFrame(byte[] data, Camera camera) {
		Log.d(TAG, "onPreviewFrame");

		Size size = camera.getParameters().getPreviewSize();
		Bitmap bitmap = ImageUtils.yuv2bitmap(data, size.width, size.height);
		Mat src = new Mat();
		Mat src2 = new Mat();
		Utils.bitmapToMat(bitmap, src);
		Core.flip(src, src2, 1);
		src2.copyTo(currentFrame);
		//flip(src, src2, 1);

		Log.d("com.example.asm.CameraPreview", "image size: w: " + src2.width()
				+ " h: " + src2.height());

		// do canny
		Mat canny_mat = new Mat();
		Imgproc.Canny(src2, canny_mat, Params.CannyParams.THRESHOLD1,
				Params.CannyParams.THRESHOLD2);
		Bitmap canny_bitmap = ImageUtils.mat2Bitmap(canny_mat);

		iv_canny.setImageBitmap(canny_bitmap);

		// do face detect in Thread
		faceDetectThread.assignTask(Params.DO_FACE_DETECT, src2);
		
		if(countFrame == 0){
			// do ASM 		
			Mat mat = new Mat();
			src2.copyTo(mat);
			new AsyncAsm(this).execute(mat);
			countFrame++;
		}
		else{
			countFrame++;
			countFrame = countFrame % 40;
		}
		
	}

	private void findAsmLandmarks(Mat src) {
		faceFound = false;
		if (asmBitmap != null) {
			asmBitmap.recycle();
		}
		Mat mat = new Mat();
		src.copyTo(mat);
		tv_info.setText("doing ASM....");
		new AsyncAsm(this).execute(mat);
	}

	
	public void determineMouth(List<Integer> list){
		double mouthUpper = list.get(62 * 2 + 1);
		double mouthBottom = list.get(74 * 2 + 1);
		
		double headUpper = list.get(14 * 2 + 1);
		double headBottom = list.get(6 * 2 + 1);
		
		double scale = (mouthUpper - mouthBottom) / (headUpper - headBottom);
		System.out.println(headUpper + ", " + headBottom + "; " + mouthUpper + ", " + mouthBottom );
		if(scale >= 0.14){
			tv_info.setText("Well Done! " + scale);
		}
		if(scale < 0.14){
			tv_info.setText("Please open your mouth bigger " + scale);
		}
	}
	
	private void drawAsmPoints(Mat src, List<Integer> list) {
		tv_info.setText("ASM Done.");
		Mat dst = new Mat();
		src.copyTo(dst);

		int[] points = new int[list.size()];
		for (int i = 0; i < list.size(); i++) {
			points[i] = list.get(i);
		}

		if (points[0] == Params.ASMError.BAD_INPUT) {
			Toast.makeText(MainActivity.this, "Cannot load image",
					Toast.LENGTH_SHORT).show();
		} else if (points[0] == Params.ASMError.INIT_FAIL) {
			Toast.makeText(MainActivity.this, "Error in stasm_search_single!",
					Toast.LENGTH_SHORT).show();
		} else if (points[0] == Params.ASMError.NO_FACE_FOUND) {
			//Toast.makeText(MainActivity.this, "No face found in input image", Toast.LENGTH_SHORT).show();
		} else {
			faceFound = true;
			for (int i = 0; i < points.length / 2 - 1; i++) {
				Point p1 = new Point();
				p1.x = points[2 * i];
				p1.y = points[2 * i + 1];

				Point p2 = new Point();
				p2.x = points[2 * (i + 1)];
				p2.y = points[2 * (i + 1) + 1];
				//Core.line(dst, p1, p2, new Scalar(255, 255, 255), 3);
				Core.circle(dst, p1, 2, new Scalar(255,255, 255), 2);
			}
			Bitmap bmp = ImageUtils.mat2Bitmap(dst);
			asmBitmap = Bitmap.createBitmap(bmp);
			iv_image_view_asm.setImageBitmap(bmp);
			
			determineMouth(list);
		}
	};

	private class AsyncAsm extends AsyncTask<Mat, Integer, List<Integer>> {
		private Context context;
		private Mat src;

		public AsyncAsm(Context context) {
			this.context = context;
		}

		@Override
		protected List<Integer> doInBackground(Mat... mat0) {
			List<Integer> list = new ArrayList<Integer>();
			Mat src = mat0[0];
			this.src = src;

			int[] points = NativeImageUtil.FindFaceLandmarks(src, 1, 1);
			for (int i = 0; i < points.length; i++) {
				list.add(points[i]);
			}

			return list;
		}

		// run on UI thread
		@Override
		protected void onPostExecute(List<Integer> list) {
			MainActivity.this.drawAsmPoints(this.src, list);
		}
	};

	private void copyDataFile2LocalDir() {
		try {
			File dataDir = this.getDir("data", Context.MODE_PRIVATE);
			File f_frontalface = new File(dataDir,
					"haarcascade_frontalface_alt2.xml");
			File f_lefteye = new File(dataDir, "haarcascade_mcs_lefteye.xml");
			File f_righteye = new File(dataDir, "haarcascade_mcs_righteye.xml");

			if (!isDataFileInLocalDir(f_frontalface, f_lefteye, f_righteye)) {
				boolean f1, f2, f3;

				f1 = putDataFileInLocalDir(MainActivity.this,
						R.raw.haarcascade_frontalface_alt2, f_frontalface);
				f2 = putDataFileInLocalDir(MainActivity.this,
						R.raw.haarcascade_mcs_lefteye, f_lefteye);
				f3 = putDataFileInLocalDir(MainActivity.this,
						R.raw.haarcascade_mcs_righteye, f_righteye);

				if (f1 && f2 && f3) {
					tv_info.setText("load cascade file successed");
				} else {
					tv_info.setText("load cascade file failed");
				}
			}
		} catch (IOError e) {
			e.printStackTrace();
		}
	}

	private boolean isDataFileInLocalDir(File f_frontalface, File f_lefteye,
			File f_righteye) {
		boolean ret = false;
		try {
			ret = f_frontalface.exists() && f_lefteye.exists()
					&& f_righteye.exists();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return ret;
	}

	/*
	 * put raw data into local DIR /data/data/com.example.asm/app_data/
	 */
	private boolean putDataFileInLocalDir(Context context, int id, File f) {
		Log.d(TAG, "putDataFileInLocalDir: " + f.toString());
		try {
			InputStream is = context.getResources().openRawResource(id);
			FileOutputStream os = new FileOutputStream(f);
			byte[] buffer = new byte[BUFFER_SIZE];
			int bytesRead;
			while ((bytesRead = is.read(buffer)) != -1) {
				os.write(buffer, 0, bytesRead);
			}
			is.close();
			os.close();
		} catch (IOException e) {
			e.printStackTrace();
			tv_info.setText("load cascade file failed");
			return false;
		}
		Log.d(TAG, "putDataFileInLocalDir: done!");
		return true;
	}
}

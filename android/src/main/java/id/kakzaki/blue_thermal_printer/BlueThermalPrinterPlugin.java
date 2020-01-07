package id.kakzaki.blue_thermal_printer;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.apache.cordova.CallbackContext;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.BarcodeEncoder;

public class BlueThermalPrinterPlugin implements MethodCallHandler, RequestPermissionsResultListener {

  private static final String TAG = "BThermalPrinterPlugin";
  private static final String NAMESPACE = "blue_thermal_printer";
  private static final int REQUEST_COARSE_LOCATION_PERMISSIONS = 1451;
  private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
  private static ConnectedThread THREAD = null;
  private final Registrar registrar;
  private BluetoothAdapter mBluetoothAdapter;

  private Result pendingResult;

  private EventSink readSink;
  private EventSink statusSink;

  public static void registerWith(Registrar registrar) {
    final BlueThermalPrinterPlugin instance = new BlueThermalPrinterPlugin(registrar);
    registrar.addRequestPermissionsResultListener(instance);
  }

  BlueThermalPrinterPlugin(Registrar registrar) {
    this.registrar = registrar;
    MethodChannel channel = new MethodChannel(registrar.messenger(), NAMESPACE + "/methods");
    EventChannel stateChannel = new EventChannel(registrar.messenger(), NAMESPACE + "/state");
    EventChannel readChannel = new EventChannel(registrar.messenger(), NAMESPACE + "/read");
		if (registrar.activity() != null){
			BluetoothManager mBluetoothManager = (BluetoothManager) registrar.activity()
							.getSystemService(Context.BLUETOOTH_SERVICE);
			assert mBluetoothManager != null;
			this.mBluetoothAdapter = mBluetoothManager.getAdapter();
		}
    channel.setMethodCallHandler(this);
    stateChannel.setStreamHandler(stateStreamHandler);
    readChannel.setStreamHandler(readResultsHandler);
  }

  // MethodChannel.Result wrapper that responds on the platform thread.
  private static class MethodResultWrapper implements Result {
    private Result methodResult;
    private Handler handler;

    MethodResultWrapper(Result result) {
      methodResult = result;
      handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void success(final Object result) {
      handler.post(new Runnable() {
        @Override
        public void run() {
          methodResult.success(result);
        }
      });
    }

    @Override
    public void error(final String errorCode, final String errorMessage, final Object errorDetails) {
      handler.post(new Runnable() {
        @Override
        public void run() {
          methodResult.error(errorCode, errorMessage, errorDetails);
        }
      });
    }

    @Override
    public void notImplemented() {
      handler.post(new Runnable() {
        @Override
        public void run() {
          methodResult.notImplemented();
        }
      });
    }
  }

  @Override
  public void onMethodCall(MethodCall call, Result rawResult) {
    Result result = new MethodResultWrapper(rawResult);

    if (mBluetoothAdapter == null && !"isAvailable".equals(call.method)) {
      result.error("bluetooth_unavailable", "the device does not have bluetooth", null);
      return;
    }

    final Map<String, Object> arguments = call.arguments();

    switch (call.method) {

      case "isAvailable":
        result.success(mBluetoothAdapter != null);
        break;

      case "isOn":
        try {
          assert mBluetoothAdapter != null;
          result.success(mBluetoothAdapter.isEnabled());
        } catch (Exception ex) {
          result.error("Error", ex.getMessage(), exceptionToString(ex));
        }
        break;

      case "isConnected":
        result.success(THREAD != null);
        break;

      case "openSettings":
        ContextCompat.startActivity(registrar.activity(), new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
                null);
        result.success(true);
        break;

      case "getBondedDevices":
        try {

          if (ContextCompat.checkSelfPermission(registrar.activity(),
                  Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(registrar.activity(),
                    new String[] { Manifest.permission.ACCESS_COARSE_LOCATION }, REQUEST_COARSE_LOCATION_PERMISSIONS);

            pendingResult = result;
            break;
          }

          getBondedDevices(result);

        } catch (Exception ex) {
          result.error("Error", ex.getMessage(), exceptionToString(ex));
        }

        break;

      case "connect":
        if (arguments.containsKey("address")) {
          String address = (String) arguments.get("address");
          connect(result, address);
        } else {
          result.error("invalid_argument", "argument 'address' not found", null);
        }
        break;

      case "disconnect":
        disconnect(result);
        break;

      case "write":
        if (arguments.containsKey("message")) {
          String message = (String) arguments.get("message");
          write(result, message);
        } else {
          result.error("invalid_argument", "argument 'message' not found", null);
        }
        break;

      case "writeBytes":
        if (arguments.containsKey("message")) {
          byte[] message = (byte[]) arguments.get("message");
          writeBytes(result, message);
        } else {
          result.error("invalid_argument", "argument 'message' not found", null);
        }
        break;

      case "printCustom":
        if (arguments.containsKey("message")) {
          String message = (String) arguments.get("message");
          int size = (int) arguments.get("size");
          int align = (int) arguments.get("align");
          printCustom(result, message, size, align);
        } else {
          result.error("invalid_argument", "argument 'message' not found", null);
        }
        break;

      case "printNewLine":
        printNewLine(result);
        break;

      case "paperCut":
        paperCut(result);
        break;

      case "printImage":
        if (arguments.containsKey("pathImage")) {
          String pathImage = (String) arguments.get("pathImage");
          printImage(result, pathImage);
        } else {
          result.error("invalid_argument", "argument 'pathImage' not found", null);
        }
        break;

      case "printQRcode":
        if (arguments.containsKey("textToQR")) {
          String textToQR = (String) arguments.get("textToQR");
          int width = (int) arguments.get("width");
          int height = (int) arguments.get("height");
          int align = (int) arguments.get("align");
          printQRcode(result, textToQR, width, height, align);
        } else {
          result.error("invalid_argument", "argument 'textToQR' not found", null);
        }
        break;
      case "printLeftRight":
        if (arguments.containsKey("string1")) {
          String string1 = (String) arguments.get("string1");
          String string2 = (String) arguments.get("string2");
          int size = (int) arguments.get("size");
          printLeftRight(result, string1, string2, size);
        } else {
          result.error("invalid_argument", "argument 'message' not found", null);
        }
        break;
      default:
        result.notImplemented();
        break;
    }
  }

  /**
   * @param requestCode  requestCode
   * @param permissions  permissions
   * @param grantResults grantResults
   * @return boolean
   */
  @Override
  public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {

    if (requestCode == REQUEST_COARSE_LOCATION_PERMISSIONS) {
      if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        getBondedDevices(pendingResult);
      } else {
        pendingResult.error("no_permissions", "this plugin requires location permissions for scanning", null);
        pendingResult = null;
      }
      return true;
    }
    return false;
  }

  /**
   * @param result result
   */
  private void getBondedDevices(Result result) {

    List<Map<String, Object>> list = new ArrayList<>();

    for (BluetoothDevice device : mBluetoothAdapter.getBondedDevices()) {
      Map<String, Object> ret = new HashMap<>();
      ret.put("address", device.getAddress());
      ret.put("name", device.getName());
      ret.put("type", device.getType());
      list.add(ret);
    }

    result.success(list);
  }

  private String exceptionToString(Exception ex) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    ex.printStackTrace(pw);
    return sw.toString();
  }

  /**
   * @param result  result
   * @param address address
   */
  private void connect(Result result, String address) {

    if (THREAD != null) {
      result.error("connect_error", "already connected", null);
      return;
    }
    AsyncTask.execute(() -> {
      try {
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);

        if (device == null) {
          result.error("connect_error", "device not found", null);
          return;
        }

        BluetoothSocket socket = device.createRfcommSocketToServiceRecord(MY_UUID);

        if (socket == null) {
          result.error("connect_error", "socket connection not established", null);
          return;
        }

        // Cancel bt discovery, even though we didn't start it
        mBluetoothAdapter.cancelDiscovery();

        try {
          socket.connect();
          THREAD = new ConnectedThread(socket);
          THREAD.start();
          result.success(true);
        } catch (Exception ex) {
          Log.e(TAG, ex.getMessage(), ex);
          result.error("connect_error", ex.getMessage(), exceptionToString(ex));
        }
      } catch (Exception ex) {
        Log.e(TAG, ex.getMessage(), ex);
        result.error("connect_error", ex.getMessage(), exceptionToString(ex));
      }
    });
  }

  /**
   * @param result result
   */
  private void disconnect(Result result) {

    if (THREAD == null) {
      result.error("disconnection_error", "not connected", null);
      return;
    }
    AsyncTask.execute(() -> {
      try {
        THREAD.cancel();
        THREAD = null;
        result.success(true);
      } catch (Exception ex) {
        Log.e(TAG, ex.getMessage(), ex);
        result.error("disconnection_error", ex.getMessage(), exceptionToString(ex));
      }
    });
  }

  /**
   * @param result  result
   * @param message message
   */
  private void write(Result result, String message) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }

    try {
      THREAD.write(message.getBytes());
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }
  }

  private void writeBytes(Result result, byte[] message) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }

    try {
      THREAD.write(message);
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }
  }

  private void printCustom(Result result, String message, int size, int align) {
    // Print config "mode"
    byte[] cc = new byte[] { 0x1B, 0x21, 0x03 }; // 0- normal size text
    // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
    byte[] bb = new byte[] { 0x1B, 0x21, 0x08 }; // 1- only bold text
    byte[] bb2 = new byte[] { 0x1B, 0x21, 0x20 }; // 2- bold with medium text
    byte[] bb3 = new byte[] { 0x1B, 0x21, 0x10 }; // 3- bold with large text
    byte[] bb4 = new byte[] { 0x1B, 0x21, 0x30 }; // 4- strong text
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }

    try {
      switch (size) {
        case 0:
          THREAD.write(cc);
          break;
        case 1:
          THREAD.write(bb);
          break;
        case 2:
          THREAD.write(bb2);
          break;
        case 3:
          THREAD.write(bb3);
          break;
        case 4:
          THREAD.write(bb4);
          break;
      }

      switch (align) {
        case 0:
          // left align
          THREAD.write(PrinterCommands.ESC_ALIGN_LEFT);
          break;
        case 1:
          // center align
          THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
          break;
        case 2:
          // right align
          THREAD.write(PrinterCommands.ESC_ALIGN_RIGHT);
          break;
      }
      THREAD.write(message.getBytes());
      THREAD.write(PrinterCommands.FEED_LINE);
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }
  }

  private void printLeftRight(Result result, String msg1, String msg2, int size) {
    byte[] cc = new byte[] { 0x1B, 0x21, 0x03 }; // 0- normal size text
    // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
    byte[] bb = new byte[] { 0x1B, 0x21, 0x08 }; // 1- only bold text
    byte[] bb2 = new byte[] { 0x1B, 0x21, 0x20 }; // 2- bold with medium text
    byte[] bb3 = new byte[] { 0x1B, 0x21, 0x10 }; // 3- bold with large text
    byte[] bb4 = new byte[] { 0x1B, 0x21, 0x30 }; // 4- strong text
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }
    try {
      switch (size) {
        case 0:
          THREAD.write(cc);
          break;
        case 1:
          THREAD.write(bb);
          break;
        case 2:
          THREAD.write(bb2);
          break;
        case 3:
          THREAD.write(bb3);
          break;
        case 4:
          THREAD.write(bb4);
          break;
      }
      THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
      String line = String.format("%-15s %15s %n", msg1, msg2);
      THREAD.write(line.getBytes());
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }

  }

  private void printNewLine(Result result) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }
    try {
      THREAD.write(PrinterCommands.FEED_LINE);
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }
  }

  private void paperCut(Result result) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }
    try {
      THREAD.write(PrinterCommands.FEED_PAPER_AND_CUT);
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }
  }

  private void printImage(Result result, String pathImage) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }
    try {
      Bitmap bmp = BitmapFactory.decodeFile(pathImage);
      if (bmp != null) {
        byte[] command = Utils.decodeBitmap(bmp);
        THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
        THREAD.write(command);
      } else {
        Log.e("Print Photo error", "the file isn't exists");
      }
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }
  }

  private void printQRcode(Result result, String textToQR, int width, int height, int align) {
    MultiFormatWriter multiFormatWriter = new MultiFormatWriter();
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }
    try {
      switch (align) {
        case 0:
          // left align
          THREAD.write(PrinterCommands.ESC_ALIGN_LEFT);
          break;
        case 1:
          // center align
          THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
          break;
        case 2:
          // right align
          THREAD.write(PrinterCommands.ESC_ALIGN_RIGHT);
          break;
      }
      BitMatrix bitMatrix = multiFormatWriter.encode(textToQR, BarcodeFormat.QR_CODE, width, height);
      BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
      Bitmap bmp = barcodeEncoder.createBitmap(bitMatrix);

      if (bmp != null) {
        byte[] command = Utils.decodeBitmap(bmp);
        THREAD.write(command);
      } else {
        Log.e("Print Photo error", "the file isn't exists");
      }
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }
  }

  private void printText(final JSONArray args, final CallbackContext callbackContext) throws JSONException {

    String sendData = args.getString(0);

    if (isConnection) {
        try {
           if(sendData!=null&&!"".equals(sendData)) {


               JSONArray top_array = new JSONArray(sendData);

               if (top_array != null && top_array.length() > 0) {
                       for (int m = 0; m < top_array.length(); m++) {
                           JSONObject jsonData = (JSONObject) top_array.get(m);
                            sendprint(jsonData);
                       }
                   }

               if(LINE_BYTE_SIZE==32){
                   THREAD.printText("\n");
                   THREAD.printText("\n");
                   THREAD.printText("\n");
                   THREAD.printText("\n");
                   THREAD.printText("\n");
               }
               //结束循环时
               THREAD.selectCommand(THREAD.getCutPaperCmd());
           }
            result.success(true);
        } catch (Exception e) {
            e.printStackTrace();
            result.error("write_error", e.getMessage(), exceptionToString(e));
          }
    } else {
      result.error("write_error");
    }
}

public void sendprint(JSONObject jsonData){


    try{
            System.out.println("jsonData:"+jsonData);
            int infoType = jsonData.optInt("infoType");
            String text = jsonData.optString("text");
            int fontType = jsonData.optInt("fontType");
            int aligmentType = jsonData.optInt("aligmentType");
            int isTitle = jsonData.optInt("isTitle");
            int maxWidth = jsonData.optInt("maxWidth");
            int qrCodeSize = jsonData.optInt("qrCodeSize");
            JSONArray textArray = jsonData.optJSONArray("textArray");

                                  /*  类型 infoType text= 0;          textList= 1;         barCode = 2;          qrCode = 3;
                                           image  = 4;         seperatorLine   = 5;            spaceLine       = 6;            footer          = 7;*/


            int fontType_int = fontType;
            int aligmentType_int = aligmentType;
            //                      int fontType_int =0;
            //                       int aligmentType_int =0;
            //                       try{
            //                           fontType_int =Integer.parseInt(fontType);
            //                       }catch (Exception e){
            //
            //                       }
            //
            //                       try{
            //                           aligmentType_int =Integer.parseInt(aligmentType);
            //                       }catch (Exception e){
            //
            //                       }

            if (isTitle == 1) {
                THREAD.write(PrinterCommands.BOLD);
            } else {
                THREAD.write(PrinterCommands.BOLD_CANCEL);
            }
          //  THREAD.write(getAlignCmd(aligmentType_int));
          //   THREAD.write(getFontSizeCmd(fontType_int));

            if (infoType == 0) {
                THREAD.printText(text);
            } else if (infoType == 1) {
                if (textArray != null && textArray.length() > 0) {
                    if (textArray.length() == 2) {
                       THREAD.printText(THREAD.printTwoData(textArray.get(0).toString(), textArray.get(1).toString()));
                    } else if (textArray.length() == 3) {
                        THREAD.printText(THREAD.printThreeData(textArray.get(0).toString(), textArray.get(1).toString(), textArray.get(2).toString()));
                    } else if (textArray.length() == 4) {
                        THREAD.printText(THREAD.printFourData(textArray.get(0).toString(), textArray.get(1).toString(), textArray.get(2).toString(), textArray.get(3).toString()));
                    }
                }
            } else if (infoType == 2) {
                THREAD.printText(getBarcodeCmd(text));
            } else if (infoType == 3) {
                // 发送二维码打印图片前导指令
                byte[] start = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1B,
                        0x40, 0x1B, 0x33, 0x00};
               THREAD.write(start);
               THREAD.write(getQrCodeCmd(text));
                // 发送结束指令
                byte[] end = {0x1d, 0x4c, 0x1f, 0x00};
                THREAD.write(end);
            } else if (infoType == 4) {
                text = text.replace("data:image/jpeg;base64,", "").replace("data:image/png;base64,", "");


                /**获取打印图片的数据**/
                byte[] bitmapArray;
                bitmapArray = Base64.decode(text, Base64.DEFAULT);
                for (int n = 0; n < bitmapArray.length; ++n) {
                    if (bitmapArray[n] < 0) {// 调整异常数据
                        bitmapArray[n] += 256;
                    }

                }

                Bitmap bitmap = BitmapFactory.decodeByteArray(bitmapArray, 0, bitmapArray.length);


                bitmap =compressPic(bitmap);

                if(bitmap!=null) {
                    //图片的长和框必须是大于24*size
                    byte[] draw2PxPoint = draw2PxPoint(bitmap);
                    //发送打印图片前导指令

                    THREAD.write(draw2PxPoint);
                }

                //图片的长和框必须是大于24*size
            //  byte[] draw2PxPoint = PicFromPrintUtils.draw2PxPoint(bitmap);
                //发送打印图片前导指令

             // THREAD.selectCommand(draw2PxPoint);




                //THREAD.selectCommand(draw2PxPoint);
                //InputStream fin = Bitmap2IS(bitmap);
               //byte[] buffer = getReadBitMapBytes(bitmap);
                //发送打印图片前导指令
               //byte[] start = { 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1B,
               //       0x40, 0x1B, 0x33, 0x00 };
             //  THREAD.selectCommand(start);
               //THREAD.selectCommandByte(buffer);
                  // 发送结束指令
              //  byte[] end = { 0x1d, 0x4c, 0x1f, 0x00 };
              //  THREAD.selectCommand(end);
                //THREAD.selectCommand(bitmapArray);
                // 发送结束指令

            } else if (infoType == 5) {
                THREAD.printText(printSeperatorLine());
            } else if (infoType == 6) {
                THREAD.printText("\n");
            } else if (infoType == 7) {
                THREAD.printText(text);
            }else if(infoType == 8) {
                //结束循环时
                THREAD.write(THREAD.getCutPaperCmd());
            }
            THREAD.printText("\n");


    } catch (Exception e) {
        e.printStackTrace();;
    }

}


/**
     * 打印两列
     *
     * @param leftText  左侧文字
     * @param rightText 右侧文字
     * @return
     */
    @SuppressLint("NewApi")
    public static String printTwoData(String leftText, String rightText) {
        StringBuilder sb = new StringBuilder();
        int leftTextLength = getBytesLength(leftText);
        int rightTextLength = getBytesLength(rightText);
        sb.append(leftText);

        // 计算两侧文字中间的空格
        int marginBetweenMiddleAndRight = LINE_BYTE_SIZE - leftTextLength - rightTextLength;

        for (int i = 0; i < marginBetweenMiddleAndRight; i++) {
            sb.append(" ");
        }
        sb.append(rightText);
        return sb.toString();
    }

    /**
     * 打印三列
     *
     * @param leftText   左侧文字
     * @param middleText 中间文字
     * @param rightText  右侧文字
     * @return
     */
    @SuppressLint("NewApi")
    public static String printThreeData(String leftText, String middleText, String rightText) {

        /**
         * 打印三列时，中间一列的中心线距离打印纸左侧的距离
         */
       int LEFT_LENGTH =LINE_BYTE_SIZE/2;

        /**
         * 打印三列时，中间一列的中心线距离打印纸右侧的距离
         */
        int RIGHT_LENGTH = LINE_BYTE_SIZE/2;

        /**
         * 打印三列时，第一列汉字最多显示几个文字
         */
        int LEFT_TEXT_MAX_LENGTH = LEFT_LENGTH/2-2;

        StringBuilder sb = new StringBuilder();
        // 左边最多显示 LEFT_TEXT_MAX_LENGTH 个汉字 + 两个点
        if (leftText.length() > LEFT_TEXT_MAX_LENGTH) {
            //leftText = leftText.substring(0, LEFT_TEXT_MAX_LENGTH) + "..";
        }
        int leftTextLength = getBytesLength(leftText);
        int middleTextLength = getBytesLength(middleText);
        int rightTextLength = getBytesLength(rightText);

        sb.append(leftText);
        // 计算左侧文字和中间文字的空格长度
        int marginBetweenLeftAndMiddle = LEFT_LENGTH - leftTextLength - middleTextLength / 2;

        for (int i = 0; i < marginBetweenLeftAndMiddle; i++) {
            sb.append(" ");
        }
        sb.append(middleText);

        // 计算右侧文字和中间文字的空格长度
        int marginBetweenMiddleAndRight = RIGHT_LENGTH - middleTextLength / 2 - rightTextLength;

        for (int i = 0; i < marginBetweenMiddleAndRight; i++) {
            sb.append(" ");
        }

        // 打印的时候发现，最右边的文字总是偏右一个字符，所以需要删除一个空格
        sb.delete(sb.length() - 1, sb.length()).append(rightText);
        return sb.toString();
    }


    /**
     * 打印四列
     *
     * @param leftText   左侧文字
     * @param middleText1 中间文字
     * @param rightText  右侧文字
     * @return
     */
    @SuppressLint("NewApi")
    public static String printFourData(String leftText, String middleText1, String middleText2, String rightText) {
        StringBuilder sb = new StringBuilder();
        /**
         * 打印三列时，中间一列的中心线距离打印纸左侧的距离
         */
        int LEFT_LENGTH =LINE_BYTE_SIZE;

        /**
         * 打印三列时，中间一列的中心线距离打印纸右侧的距离
         */
      //  int RIGHT_LENGTH_1 = LEFT_LENGTH-20;
        int RIGHT_LENGTH_2 = 6;
        int RIGHT_LENGTH_3 = 6;
        int RIGHT_LENGTH_4 = 8;
        int RIGHT_LENGTH_1 = LEFT_LENGTH-RIGHT_LENGTH_2-RIGHT_LENGTH_3-RIGHT_LENGTH_4;
        /**
         * 打印三列时，第一列汉字最多显示几个文字
         */

        int sub_length=2;
        if(LINE_BYTE_SIZE==32){
            sub_length=0;
        }

        int leftTextLength = getBytesLength(leftText);
        int middle1TextLength = getBytesLength(middleText1);
        int middle2TextLength = getBytesLength(middleText2);
       // int rightTextLength = getBytesLength(rightText);

        sb.append(leftText);

        for (int i = leftTextLength; i < RIGHT_LENGTH_1; i++) {
            sb.append(" ");
        }

        sb.append(middleText1);

        for (int i = RIGHT_LENGTH_1+middle1TextLength; i < RIGHT_LENGTH_1+RIGHT_LENGTH_2; i++) {
            sb.append(" ");
        }
        sb.append(middleText2);

        for (int i = RIGHT_LENGTH_1+RIGHT_LENGTH_2+middle2TextLength; i < RIGHT_LENGTH_1+RIGHT_LENGTH_2+RIGHT_LENGTH_3; i++) {
            sb.append(" ");
        }

        sb.append(rightText);


        // 打印的时候发现，最右边的文字总是偏右一个字符，所以需要删除一个空格
       // sb.delete(sb.length() - 3, sb.length()).append(rightText);
        return sb.toString();
    }
    /**
     * 打印四列
     *
     * @param leftText   左侧文字
     * @param middleText1 中间文字
     * @param rightText  右侧文字
     * @return
     */
    @SuppressLint("NewApi")
    public static String printFourDataOld(String leftText, String middleText1, String middleText2, String rightText) {
        StringBuilder sb = new StringBuilder();
        /**
         * 打印三列时，中间一列的中心线距离打印纸左侧的距离
         */
        int LEFT_LENGTH =LINE_BYTE_SIZE/2;

        /**
         * 打印三列时，中间一列的中心线距离打印纸右侧的距离
         */
        int RIGHT_LENGTH = LINE_BYTE_SIZE/2;

        /**
         * 打印三列时，第一列汉字最多显示几个文字
         */

        int sub_length=2;
        if(LINE_BYTE_SIZE==32){
            sub_length=0;
        }

        int sub_length2=1;
       // if(LINE_BYTE_SIZE==32){
          //  sub_length2=1;
      //  }

        int LEFT_TEXT_MAX_LENGTH = LEFT_LENGTH/2-sub_length;

        // 左边最多显示 LEFT_TEXT_MAX_LENGTH 个汉字 + 两个点
        if (leftText.length() > (LEFT_TEXT_MAX_LENGTH+2)/2) {
            //leftText = leftText.substring(0, (LEFT_TEXT_MAX_LENGTH+2)/2-1) + ".";
        }
        int leftTextLength = getBytesLength(leftText);
        int middle1TextLength = getBytesLength(middleText1);
        int middle2TextLength = getBytesLength(middleText2);
        int rightTextLength = getBytesLength(rightText);

        sb.append(leftText);
        // 计算左侧文字和中间文字的空格长度
        int marginBetweenLeftAndMiddle1 = LEFT_LENGTH- leftTextLength - middle1TextLength ;

        for (int i = LEFT_LENGTH/4-sub_length2; i < marginBetweenLeftAndMiddle1; i++) {
            sb.append(" ");
        }
        sb.append(middleText1);


        // 计算右侧文字和中间文字的空格长度
        int marginBetweenMiddleAndRight = RIGHT_LENGTH- middle2TextLength - rightTextLength;

        for (int i = RIGHT_LENGTH/4-sub_length2; i < marginBetweenMiddleAndRight; i++) {
            sb.append(" ");
        }
        sb.append(middleText2);

        // 计算右侧文字和中间文字的空格长度
        int marginBetweenMiddle2AndRight = RIGHT_LENGTH - middle2TextLength  - rightTextLength;

        for (int i = RIGHT_LENGTH/4-sub_length2; i < marginBetweenMiddle2AndRight; i++) {
            sb.append(" ");
        }
        // 打印的时候发现，最右边的文字总是偏右一个字符，所以需要删除一个空格
        sb.delete(sb.length() - 3, sb.length()).append(rightText);
        return sb.toString();
    }


  private class ConnectedThread extends Thread {
    private final BluetoothSocket mmSocket;
    private final InputStream inputStream;
    private final OutputStream outputStream;

    ConnectedThread(BluetoothSocket socket) {
      mmSocket = socket;
      InputStream tmpIn = null;
      OutputStream tmpOut = null;

      try {
        tmpIn = socket.getInputStream();
        tmpOut = socket.getOutputStream();
      } catch (IOException e) {
        e.printStackTrace();
      }
      inputStream = tmpIn;
      outputStream = tmpOut;
    }

    public void run() {
      byte[] buffer = new byte[1024];
      int bytes;
      while (true) {
        try {
          bytes = inputStream.read(buffer);
          readSink.success(new String(buffer, 0, bytes));
        } catch (NullPointerException e) {
          break;
        } catch (IOException e) {
          break;
        }
      }
    }

    public void write(byte[] bytes) {
      try {
        outputStream.write(bytes);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    public void cancel() {
      try {
        outputStream.flush();
        outputStream.close();

        inputStream.close();

        mmSocket.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  private final StreamHandler stateStreamHandler = new StreamHandler() {

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();

        Log.d(TAG, action);

        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
          THREAD = null;
          statusSink.success(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
        } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
          statusSink.success(1);
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
          THREAD = null;
          statusSink.success(0);
        }
      }
    };

    @Override
    public void onListen(Object o, EventSink eventSink) {
      statusSink = eventSink;
      registrar.activity().registerReceiver(mReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

      registrar.activeContext().registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED));

      registrar.activeContext().registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED));
    }

    @Override
    public void onCancel(Object o) {
      statusSink = null;
      registrar.activity().unregisterReceiver(mReceiver);
    }
  };

  private final StreamHandler readResultsHandler = new StreamHandler() {
    @Override
    public void onListen(Object o, EventSink eventSink) {
      readSink = eventSink;
    }

    @Override
    public void onCancel(Object o) {
      readSink = null;
    }
  };
}
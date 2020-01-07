package id.kakzaki.blue_thermal_printer;

/**
 * Created by https://goo.gl/UAfmBd on 2/6/2017.
 */

public class PrinterCommands {
    public static final byte HT = 0x9;
    public static final byte LF = 0x0A;
    public static final byte CR = 0x0D;
    public static final byte ESC = 0x1B;
    public static final byte DLE = 0x10;
    public static final byte GS = 0x1D;
    public static final byte FS = 0x1C;
    public static final byte STX = 0x02;
    public static final byte US = 0x1F;
    public static final byte CAN = 0x18;
    public static final byte CLR = 0x0C;
    public static final byte EOT = 0x04;

    public static final byte[] INIT = {27, 64};
    public static byte[] FEED_LINE = {10};

    public static byte[] SELECT_FONT_A = {20, 33, 0};

    public static byte[] SET_BAR_CODE_HEIGHT = {29, 104, 100};
    public static byte[] PRINT_BAR_CODE_1 = {29, 107, 2};
    public static byte[] SEND_NULL_BYTE = {0x00};

    public static byte[] SELECT_PRINT_SHEET = {0x1B, 0x63, 0x30, 0x02};
    public static byte[] FEED_PAPER_AND_CUT = {0x1D, 0x56, 66, 0x00};

    public static byte[] SELECT_CYRILLIC_CHARACTER_CODE_TABLE = {0x1B, 0x74, 0x11};

    public static byte[] SELECT_BIT_IMAGE_MODE = {0x1B, 0x2A, 33, -128, 0};
    public static byte[] SET_LINE_SPACING_24 = {0x1B, 0x33, 24};
    public static byte[] SET_LINE_SPACING_30 = {0x1B, 0x33, 30};

    public static byte[] TRANSMIT_DLE_PRINTER_STATUS = {0x10, 0x04, 0x01};
    public static byte[] TRANSMIT_DLE_OFFLINE_PRINTER_STATUS = {0x10, 0x04, 0x02};
    public static byte[] TRANSMIT_DLE_ERROR_STATUS = {0x10, 0x04, 0x03};
    public static byte[] TRANSMIT_DLE_ROLL_PAPER_SENSOR_STATUS = {0x10, 0x04, 0x04};

    public static final byte[] ESC_FONT_COLOR_DEFAULT = new byte[] { 0x1B, 'r',0x00 };
    public static final byte[] FS_FONT_ALIGN = new byte[] { 0x1C, 0x21, 1, 0x1B,
            0x21, 1 };
    public static final byte[] ESC_ALIGN_LEFT = new byte[] { 0x1b, 'a', 0x00 };
    public static final byte[] ESC_ALIGN_RIGHT = new byte[] { 0x1b, 'a', 0x02 };
    public static final byte[] ESC_ALIGN_CENTER = new byte[] { 0x1b, 'a', 0x01 };
    public static final byte[] ESC_CANCEL_BOLD = new byte[] { 0x1B, 0x45, 0 };


    /*********************************************/
    public static final byte[] ESC_HORIZONTAL_CENTERS = new byte[] { 0x1B, 0x44, 20, 28, 00};
    public static final byte[] ESC_CANCLE_HORIZONTAL_CENTERS = new byte[] { 0x1B, 0x44, 00 };
    /*********************************************/

    public static final byte[] ESC_ENTER = new byte[] { 0x1B, 0x4A, 0x40 };
    public static final byte[] PRINTE_TEST = new byte[] { 0x1D, 0x28, 0x41 };
    
    /*
    * 复位打印机
    */
   public static final byte[] RESET = {0x1b, 0x40};

   /**
    * 左对齐
    */
   public static final byte[] ALIGN_LEFT = {0x1b, 0x61, 0x00};

   /**
    * 中间对齐
    */
   public static final byte[] ALIGN_CENTER = {0x1b, 0x61, 0x01};

   /**
    * 右对齐
    */
   public static final byte[] ALIGN_RIGHT = {0x1b, 0x61, 0x02};

   /**
    * 选择加粗模式
    */
   public static final byte[] BOLD = {0x1b, 0x45, 0x01};

   /**
    * 取消加粗模式
    */
   public static final byte[] BOLD_CANCEL = {0x1b, 0x45, 0x00};

   /**
    * 宽高加倍
    */
   public static final byte[] DOUBLE_HEIGHT_WIDTH = {0x1d, 0x21, 0x11};

   /**
    * 宽加倍
    */
   public static final byte[] DOUBLE_WIDTH = {0x1d, 0x21, 0x10};

   /**
    * 高加倍
    */
   public static final byte[] DOUBLE_HEIGHT = {0x1d, 0x21, 0x01};

   /**
    * 字体不放大
    */
   public static final byte[] NORMAL = {0x1d, 0x21, 0x00};

   /**
    * 设置默认行间距
    */
   public static final byte[] LINE_SPACING_DEFAULT = {0x1b, 0x32};

   /**
    * 打印纸一行最大的字节
    */
   public static  int LINE_BYTE_SIZE = 48;

   // 对齐方式
   public static final int ALIGN_LEFT_NEW = 0;     // 靠左
   public static final int ALIGN_CENTER_NEW = 1;   // 居中
   public static final int ALIGN_RIGHT_NEW  = 2;    // 靠右

   //字体大小
   public static final int FONT_NORMAL_NEW  = 0;    // 正常
   public static final int FONT_MIDDLE_NEW = 1;    // 中等
   public static final int FONT_BIG_NEW  = 2;       // 大
   public static final int FONT_BIG_NEW3 = 3;    // 字体3
   public static final int FONT_BIG_NEW4  = 4;       // 字体4
   public static final int FONT_BIG_NEW5 = 5;    // 字体5
   public static final int FONT_BIG_NEW6  = 6;       // 字体6
   public static final int FONT_BIG_NEW7  = 7;    // 字体7
   public static final int FONT_BIG_NEW8  = 8;       // 字体8
   //加粗模式
   public static final int FONT_BOLD_NEW  = 0;              // 字体加粗
   public static final int FONT_BOLD_CANCEL_NEW  = 1;       // 取消加粗

}
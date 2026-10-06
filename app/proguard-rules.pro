# ML Kit and CameraX ship their own consumer ProGuard rules; nothing extra is needed.
# Keep the custom view so it can be inflated from XML after shrinking.
-keep class com.example.eyebot.VectorFaceView { <init>(...); }

# usb-serial-for-android finds drivers via reflection (getSupportedDevices).
-keep class com.hoho.android.usbserial.driver.** { *; }

# v4: TensorFlow Lite + MediaPipe use JNI and reflection.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**

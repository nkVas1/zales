# Zales R8 rules.
#
# Keep line numbers so a stack trace pasted by a user is actually readable,
# but hide the original source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── The native bridge ──────────────────────────────────────────────────────
#
# Go finds these classes by their descriptor at runtime, through JNI. A rename
# is not a compile error and not a warning: it is a crash the first time the
# tunnel is opened, on a device we do not have.
#
# The zales-core AAR ships a rule of its own that covers this, and it is far
# broader than it needs to be — it keeps the whole `io.github.nkvas1.zales`
# tree, which is the entire application. These are stated here anyway so that
# the day that AAR narrows its rule, or is replaced, the bridge does not
# silently stop working.
-keep class go.** { *; }
-keep class io.github.nkvas1.zales.zalescore.** { *; }

# The protector is a Kotlin lambda handed across the boundary and called back
# from Go. Its interface has to survive under its own name.
-keep interface io.github.nkvas1.zales.zalescore.Protector { *; }

# ── Across the process boundary ────────────────────────────────────────────
#
# AIDL stubs are reached through Binder, which resolves the interface
# descriptor by string. The Parcelable CREATORs are read reflectively by the
# framework; the default AGP rules cover that, and it is spelled out here
# because a silent failure to unparcel looks like a tunnel that simply never
# reports its state.
-keep class io.github.nkvas1.zales.tunnel.service.ITunnelService { *; }
-keep class io.github.nkvas1.zales.tunnel.service.ITunnelService$* { *; }
-keep class io.github.nkvas1.zales.tunnel.service.ITunnelCallback { *; }
-keep class io.github.nkvas1.zales.tunnel.service.ITunnelCallback$* { *; }
-keepclassmembers class io.github.nkvas1.zales.tunnel.service.** implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ── Declared in the manifest ───────────────────────────────────────────────
#
# The system instantiates these by name. AGP generates keep rules from the
# manifest, so this is belt and braces — but the cost of being wrong is an app
# that installs, launches, and then has no tunnel, no tile and no widget.
-keep class io.github.nkvas1.zales.MainActivity
-keep class io.github.nkvas1.zales.ZalesApp
-keep class io.github.nkvas1.zales.tunnel.service.ZalesVpnService
-keep class io.github.nkvas1.zales.tunnel.service.ZalesTileService
-keep class io.github.nkvas1.zales.tunnel.service.ZalesWidget
-keep class io.github.nkvas1.zales.tunnel.service.BootReceiver

# ── Quiet ──────────────────────────────────────────────────────────────────
#
# ZXing compiles against javax.imageio and java.awt for its desktop entry
# points, which no Android build has and no code here reaches.
-dontwarn java.awt.**
-dontwarn javax.imageio.**

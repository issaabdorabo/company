# Keep metadata needed by Kotlin, Moshi and reflection-based framework code.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# Room locates the generated database implementation by its conventional class name.
-keepnames class * extends androidx.room.RoomDatabase

# Moshi code generation uses generated adapters discovered by their conventional names.
-keep class **JsonAdapter { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }

# Preserve fields explicitly mapped with Moshi annotations.
-keepclassmembers,allowobfuscation class * {
    @com.squareup.moshi.Json <fields>;
}

# Retain source line numbers for useful crash diagnostics without retaining source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


# Keep Credential Manager Play Services bridge classes for native Google sign-in.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
    *;
}

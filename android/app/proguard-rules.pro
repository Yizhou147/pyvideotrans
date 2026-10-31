# Native entry points are referenced from Java/Kotlin declarations.
-keepclasseswithmembers,allowoptimization class * {
    native <methods>;
}

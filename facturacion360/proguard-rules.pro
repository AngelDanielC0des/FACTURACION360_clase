# Ofuscación básica: conservar el código y cambiar solo nombres permitidos.
-dontshrink
-dontoptimize

# Mantener información utilizada por Spring, Jackson y los records de Java.
-keepattributes *Annotation*,Signature,Exceptions,InnerClasses,EnclosingMethod,MethodParameters,Record,NestHost,NestMembers,SourceFile,LineNumberTable
-keepparameternames
-keepdirectories

# Conservar clases, campos y métodos no privados, incluida la entrada main.
# Solo podrán renombrarse los métodos privados que no estén anotados.
-keep class edu.xtd.facturacion360.** {
    <fields>;
    !private <methods>;
    <init>(...);
}

# Respetar los métodos anotados que un framework pueda invocar por reflexión.
-keepclassmembers class edu.xtd.facturacion360.** {
    @** <methods>;
}

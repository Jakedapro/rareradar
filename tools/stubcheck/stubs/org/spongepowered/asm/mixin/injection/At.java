package org.spongepowered.asm.mixin.injection;
import java.lang.annotation.*;
@Target({}) @Retention(RetentionPolicy.RUNTIME)
public @interface At {
    String id() default "";
    String value();
    String target() default "";
    int ordinal() default -1;
}

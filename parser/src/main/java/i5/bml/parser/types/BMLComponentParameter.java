package i5.bml.parser.types;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface BMLComponentParameter {

    String name();

    BuiltinType expectedBMLType();

    boolean isRequired();
}

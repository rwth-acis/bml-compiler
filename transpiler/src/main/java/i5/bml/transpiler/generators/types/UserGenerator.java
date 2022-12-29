package i5.bml.transpiler.generators.types;

import i5.bml.parser.types.BMLUser;
import i5.bml.transpiler.bot.events.messenger.User;
import i5.bml.transpiler.generators.CodeGenerator;
import i5.bml.transpiler.generators.Generator;
import i5.bml.transpiler.generators.HasBotClass;
import org.antlr.symtab.Type;

@CodeGenerator(typeClass = BMLUser.class)
public class UserGenerator implements Generator, HasBotClass {

    public UserGenerator(Type userType) {}

    @Override
    public Class<?> getBotClass() {
        return User.class;
    }
}
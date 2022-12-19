package i5.bml.transpiler;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.VarType;
import generatedParser.BMLBaseVisitor;
import generatedParser.BMLParser;
import i5.bml.parser.symbols.BlockScope;
import i5.bml.parser.types.BMLFunction;
import i5.bml.parser.types.BMLType;
import i5.bml.parser.types.BuiltinType;
import i5.bml.parser.types.TypeRegistry;
import i5.bml.parser.types.dialogue.BMLState;
import i5.bml.transpiler.bot.dialogue.DialogueAutomaton;
import i5.bml.transpiler.bot.dialogue.State;
import i5.bml.transpiler.bot.events.messenger.MessageEventContext;
import i5.bml.transpiler.generators.Generator;
import i5.bml.transpiler.generators.GeneratorRegistry;
import org.antlr.symtab.Scope;
import org.antlr.symtab.VariableSymbol;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JavaSynthesizer extends BMLBaseVisitor<Node> {

    private String botOutputPath = "transpiler/src/main/java/i5/bml/transpiler/bot/";

    private Scope currentScope;

    private Scope globalScope;

    private Scope dialogueScope = new BlockScope(null);

    private void pushScope(Scope s) {
        currentScope = s;
    }

    private void popScope() {
        currentScope = currentScope.getEnclosingScope();
    }

    @Override
    public Node visitBotDeclaration(BMLParser.BotDeclarationContext ctx) {
        pushScope(ctx.scope);
        globalScope = ctx.scope;
        var result = super.visitBotDeclaration(ctx);
        popScope();
        return result;
    }

    @Override
    public Node visitBotHead(BMLParser.BotHeadContext ctx) {
        try {
            var botConfig = new File(botOutputPath + "BotConfig.java");
            CompilationUnit c = StaticJavaParser.parse(botConfig);
            //noinspection OptionalGetWithoutIsPresent -> We can assume that the class is present
            var clazz = c.getClassByName("BotConfig").get();
            System.out.println(c);

            for (var pair : ctx.params.elementExpressionPair()) {
                var type = BMLTypeResolver.resolveBMLTypeToJavaType(pair.expr.type);
                var name = pair.name.getText().toUpperCase();
                clazz.addFieldWithInitializer(type, name, (Expression) visit(pair.expr),
                        Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);
            }

            // Write back to botConfig
            Files.write(botConfig.toPath(), c.toString().getBytes());
        } catch (FileNotFoundException e) {
            throw new IllegalStateException("Could not find %s in %s".formatted("BotConfig.java", botOutputPath));
        } catch (IOException e) {
            throw new IllegalStateException("Error writing to file %s%s: %s"
                    .formatted(botOutputPath, "BotConfig.java", e.getMessage()));
        }

        return super.visitBotHead(ctx);
    }

    @Override
    public Node visitElementExpressionPairList(BMLParser.ElementExpressionPairListContext ctx) {
        pushScope(ctx.scope);
        var result = super.visitElementExpressionPairList(ctx);
        popScope();
        return result;
    }

    @Override
    public Node visitBotBody(BMLParser.BotBodyContext ctx) {
        // TODO:
        ctx.component();

        return super.visitBotBody(ctx);
    }

    @Override
    public Node visitComponent(BMLParser.ComponentContext ctx) {
        return GeneratorRegistry.getGeneratorForType(ctx.type).generateComponent(ctx, this);
    }

    @Override
    public Node visitFunctionDefinition(BMLParser.FunctionDefinitionContext ctx) {
        // TODO: Make distinction for different Annotations

        pushScope(ctx.scope);
        var result = super.visitFunctionDefinition(ctx);
        popScope();
        return result;
    }

    @Override
    public Node visitStatement(BMLParser.StatementContext ctx) {
        pushScope(ctx.scope);
        var result = super.visitStatement(ctx);
        popScope();
        return result;
    }

    @Override
    public Node visitBlock(BMLParser.BlockContext ctx) {
        return new BlockStmt(ctx.statement().stream()
                .map(statementContext -> {
                    var node = visit(statementContext);
                    return !(node instanceof Statement) ? new ExpressionStmt((Expression) node) : (Statement) node;
                })
                .collect(Collectors.toCollection(NodeList::new))
        );
    }

    @Override
    public Node visitIfStatement(BMLParser.IfStatementContext ctx) {
        var elseStmt = ctx.elseStmt == null ? null : (Statement) visit(ctx.elseStmt);
        return new IfStmt((Expression) visit(ctx.expr), (Statement) visit(ctx.thenStmt), elseStmt);
    }

    /**
     * We cannot use .forEach(Consumer c) because the consumer c expects final variables,
     * this would greatly complicate code generation. Hence, we go for the simple good 'n' old
     * `enhanced for statement`:<br>
     * <pre>
     *     for (var i : list) { // For lists
     *         // Do something
     *     }
     *
     *     for (var e : map.entrySet()) {
     *         var key = e.getKey();
     *         var value = e.getValue();
     *         // Do something
     *     }
     * </pre>
     *
     * @param ctx the parse tree
     * @return the freshly created forEach statement instance of
     * <a href="https://javadoc.io/doc/com.github.javaparser/javaparser-core/latest/index.html">ForEachStmt</a>
     * @implNote The lists or maps we are working on are synchronized or concurrent by construction.
     */
    @Override
    public Node visitForEachStatement(BMLParser.ForEachStatementContext ctx) {
        VariableDeclarationExpr variable;
        BlockStmt forEachBody = (BlockStmt) visit(ctx.forEachBody());
        if (ctx.comma == null) { // List
            variable = new VariableDeclarationExpr(new VarType(), ctx.Identifier(0).getText());
        } else { // Map
            var mapEntryVarName = "e";
            variable = new VariableDeclarationExpr(new VarType(), mapEntryVarName);

            forEachBody.addStatement(0, new VariableDeclarationExpr(new VariableDeclarator(new VarType(),
                    ctx.Identifier(0).getText(), new MethodCallExpr(new NameExpr(mapEntryVarName), "getKey"))));
            forEachBody.addStatement(0, new VariableDeclarationExpr(new VariableDeclarator(new VarType(),
                    ctx.Identifier(1).getText(), new MethodCallExpr(new NameExpr(mapEntryVarName), "getValue"))));
        }

        return new ForEachStmt(variable, (Expression) visit(ctx.expr), forEachBody);
    }

    @Override
    public Node visitAssignment(BMLParser.AssignmentContext ctx) {
        if (ctx.op.getType() == BMLParser.ASSIGN) {
            var type = BMLTypeResolver.resolveBMLTypeToJavaType(ctx.expr.type);
            return new ExpressionStmt(new VariableDeclarationExpr(new VariableDeclarator(type, ctx.name.getText(), (Expression) visit(ctx.expr))));
        } else {
            switch (ctx.op.getType()) {
                // TODO
            }

            return null;
        }
    }

    @Override
    public Node visitExpression(BMLParser.ExpressionContext ctx) {
        if (ctx.atom() != null) {
            return visit(ctx.atom());
        } else if (ctx.op != null) {
            return switch (ctx.op.getType()) {
                case BMLParser.LBRACE -> new EnclosedExpr((Expression) visit(ctx.expr));

                case BMLParser.DOT -> {
                    Generator generator = GeneratorRegistry.getGeneratorForType(ctx.expr.type);
                    if (ctx.Identifier() != null) {
                        yield generator.generateFieldAccess((Expression) visit(ctx.expr), ctx.Identifier());
                    } else { // functionCall
                        yield generator.generateFunctionCall(ctx.functionCall(), this);
                    }
                }

                case BMLParser.LBRACK -> new MethodCallExpr((Expression) visit(ctx.expr), "get",
                        new NodeList<>((Expression) visit(ctx.index)));

                case BMLParser.BANG ->
                        new UnaryExpr((Expression) visit(ctx.expr), UnaryExpr.Operator.LOGICAL_COMPLEMENT);

                case BMLParser.LT, BMLParser.LE, BMLParser.GT, BMLParser.GE, BMLParser.EQUAL, BMLParser.NOTEQUAL,
                        BMLParser.ADD, BMLParser.SUB, BMLParser.MUL, BMLParser.DIV, BMLParser.MOD ->
                    //noinspection OptionalGetWithoutIsPresent -> Our operators are a subset of Java's, so they exist
                        new BinaryExpr((Expression) visit(ctx.left), (Expression) visit(ctx.right),
                                Arrays.stream(BinaryExpr.Operator.values())
                                        .filter(op -> op.asString().equals(ctx.op.getText()))
                                        .findAny()
                                        .get());

                case BMLParser.AND -> new BinaryExpr((Expression) visit(ctx.left), (Expression) visit(ctx.right),
                        BinaryExpr.Operator.AND);

                case BMLParser.OR -> new BinaryExpr((Expression) visit(ctx.left), (Expression) visit(ctx.right),
                        BinaryExpr.Operator.OR);

                case BMLParser.QUESTION ->
                        new ConditionalExpr((Expression) visit(ctx.cond), (Expression) visit(ctx.thenExpr),
                                (Expression) visit(ctx.elseExpr));

                // This should never happen
                default -> throw new IllegalStateException("Unexpected ctx.op: %s\nContext: %s".formatted(ctx.op, ctx));
            };
        } else if (ctx.functionCall() != null) {
            return visit(ctx.functionCall());
        } else { // Initializers
            return visit(ctx.initializer());
        }
    }

    @Override
    public Node visitAtom(BMLParser.AtomContext ctx) {
        var atom = ctx.token.getText();
        return switch (ctx.token.getType()) {
            case BMLParser.IntegerLiteral -> new IntegerLiteralExpr(atom);
            case BMLParser.FloatingPointLiteral -> new DoubleLiteralExpr(atom);
            case BMLParser.StringLiteral -> new StringLiteralExpr(atom.substring(1, atom.length() - 1));
            case BMLParser.BooleanLiteral -> new BooleanLiteralExpr(Boolean.parseBoolean(atom));
            case BMLParser.Identifier -> {
                // Check global scope
                var symbol = ((VariableSymbol) globalScope.getSymbol(atom));
                if (symbol != null
                        && (symbol.getType().equals(TypeRegistry.resolveType(BuiltinType.BOOLEAN))
                        || symbol.getType().equals(TypeRegistry.resolveType(BuiltinType.NUMBER)))) {
                    // We have a global variable -> needs thread-safety
                    yield new MethodCallExpr(new NameExpr(atom), "getAcquire");
                }

                // Check dialogue scope, not function scope, only "global" dialogue scope
                symbol = ((VariableSymbol) dialogueScope.getSymbol(atom));
                if (symbol != null) {
                    yield new MethodCallExpr(new NameExpr("dialogueAutomaton"), "get" + StringUtils.capitalize(atom));
                }

                // No special access required
                yield new NameExpr(atom);
            }
            // This should never happen
            default ->
                    throw new IllegalStateException("Unknown token was parsed: %s\nContext: %s".formatted(atom, ctx));
        };
    }

    @Override
    public Node visitFunctionCall(BMLParser.FunctionCallContext ctx) {
        // TODO: These calls can only be STDLIB calls
        return new MethodCallExpr(ctx.functionName.getText());
    }

    @Override
    public Node visitMapInitializer(BMLParser.MapInitializerContext ctx) {
        var elementExpressionPairList = ctx.elementExpressionPairList();
        if (elementExpressionPairList != null) {
            var arguments = elementExpressionPairList.elementExpressionPair().stream()
                    .flatMap(p -> Stream.of(new StringLiteralExpr(p.name.getText()), (Expression) visit(p.expr)))
                    .collect(Collectors.toCollection(NodeList::new));
            return new MethodCallExpr(new NameExpr("Map"), "of", arguments);
        } else {
            return new MethodCallExpr(new NameExpr("Map"), "of");
        }
    }

    @Override
    public Node visitListInitializer(BMLParser.ListInitializerContext ctx) {
        var arguments = ctx.expression().stream()
                .map(e -> (Expression) visit(e))
                .collect(Collectors.toCollection(NodeList::new));
        return new MethodCallExpr(new NameExpr("List"), new SimpleName("of"), arguments);
    }

    @Override
    public Node visitDialogueAutomaton(BMLParser.DialogueAutomatonContext ctx) {
        dialogueScope = ctx.scope;
        pushScope(dialogueScope);
        var childNode = super.visitDialogueAutomaton(ctx);
        popScope();
        dialogueScope = new BlockScope(null);
        return childNode;
    }

    @Override
    public Node visitDialogueHead(BMLParser.DialogueHeadContext ctx) {
        return super.visitDialogueHead(ctx);
    }

    @Override
    public Node visitDialogueBody(BMLParser.DialogueBodyContext ctx) {
        if (!ctx.functionDefinition().isEmpty()) {
            readAndWriteClass("dialogue/Actions.java", "Actions", clazz -> {
                for (var functionDefinitionContext : ctx.functionDefinition()) {
                    var actionMethod = clazz.addMethod(functionDefinitionContext.head.functionName.getText(),
                            Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC);
                    actionMethod.addParameter(MessageEventContext.class, "context");
                    actionMethod.setBody((BlockStmt) visit(functionDefinitionContext.body));
                }
            });
        }

        if (!ctx.dialogueAssignment().isEmpty()) {
            readAndWriteClass("dialogue/DialogueAutomaton.java", "DialogueAutomaton", clazz -> {
                ctx.dialogueAssignment().forEach(c -> {
                    if (c.assignment().expr.type.equals(TypeRegistry.resolveType(BuiltinType.STATE))) {
                        visit(c);
                    } else {
                        var field = clazz.addFieldWithInitializer(BMLTypeResolver.resolveBMLTypeToJavaType(c.assignment().expr.type),
                                c.assignment().name.getText(), (Expression) visit(c.assignment().expr),
                                Modifier.Keyword.PRIVATE, Modifier.Keyword.FINAL);

                        field.createGetter();
                        field.createSetter();
                    }
                });
            });
        }

        for (var automatonTransition : ctx.automatonTransitions()) {
            visit(automatonTransition);
        }

        return null;
    }

    @Override
    public Node visitDialogueAssignment(BMLParser.DialogueAssignmentContext ctx) {
        var childNode = super.visitDialogueAssignment(ctx);

        if (ctx.assignment().expr.type.equals(TypeRegistry.resolveType(BuiltinType.STATE))) {
            // Create a class
            CompilationUnit c = new CompilationUnit();
            var className = StringUtils.capitalize(ctx.assignment().name.getText()) + "State";
            var clazz = c.addClass(className);
            clazz.addExtendedType(State.class);

            var stateType = (BMLState) ctx.assignment().expr.type;

            // Add field to store & set intent
            clazz.addField(DialogueAutomaton.class, "dialogueAutomaton", Modifier.Keyword.PRIVATE, Modifier.Keyword.FINAL);
            var constructorBody = new BlockStmt().addStatement(new FieldAccessExpr(new NameExpr("this"), "dialogueAutomaton"))
                    .addStatement(new AssignExpr(new FieldAccessExpr(new NameExpr("super"), "intent"),
                            new StringLiteralExpr(stateType.getIntent()), AssignExpr.Operator.ASSIGN));
            clazz.addConstructor()
                    .setParameters(new NodeList<>(new Parameter(StaticJavaParser.parseType(DialogueAutomaton.class.getSimpleName()), "dialogueAutomaton")))
                    .setBody(constructorBody);

            // Set action
            var actionMethod = clazz.addMethod("action", Modifier.Keyword.PUBLIC);
            actionMethod.addAnnotation(Override.class);
            actionMethod.addParameter(MessageEventContext.class, "context");

            var block = switch (stateType.getActionType().getClass().getAnnotation(BMLType.class).name()) {
                case STRING ->
                        new BlockStmt().addStatement(new MethodCallExpr(new NameExpr("MessageHelper"), "replyToMessenger",
                                new NodeList<>(new NameExpr("context"), new StringLiteralExpr(((String) stateType.getAction())))));
                case LIST -> {
                    var randomType = StaticJavaParser.parseClassOrInterfaceType("Random");
                    clazz.addFieldWithInitializer(Random.class, "random",
                            new ObjectCreationExpr(null, randomType, new NodeList<>()), Modifier.Keyword.PRIVATE, Modifier.Keyword.FINAL);

                    var getRandomIntStmt = new VariableDeclarationExpr(new VariableDeclarator(new VarType(), "nextMessage",
                            new MethodCallExpr(new NameExpr("random"), "nextInt", new NodeList<>(new IntegerLiteralExpr("3")))));

                    var sendMessageStmt = new MethodCallExpr(new NameExpr("MessageHelper"), "replyToMessenger",
                            new NodeList<>(new NameExpr("context"), new StringLiteralExpr(((String) stateType.getAction()))));

                    yield new BlockStmt().addStatement(getRandomIntStmt).addStatement(sendMessageStmt);
                }
                case FUNCTION -> {
                    //((String) stateType.getAction()); // Function name

                }
                default -> new BlockStmt();
            };

            actionMethod.setBody(block);

            // Create file at desired destination
            writeClass("dialogue/states", className);
        }

        return childNode;
    }

    @Override
    public Node visitAutomatonTransitions(BMLParser.AutomatonTransitionsContext ctx) {
        var children = ctx.children;
        var firstState = children.get(0);
        var stateCounter = 1;
        var stateClassType = StaticJavaParser.parseClassOrInterfaceType("State");
        String currStateName = "";
        var initBlock = new BlockStmt();
        String intent = "";

        if (firstState instanceof BMLParser.FunctionCallContext funcCall) {
            currStateName = "state" + stateCounter;
            var newStateExpr = new ObjectCreationExpr(null, stateClassType, new NodeList<>());
            initBlock.addStatement(new VariableDeclarationExpr(new VariableDeclarator(new VarType(), currStateName, newStateExpr)));
            ++stateCounter;

            // Retrieve intent
            intent = ((BMLState) funcCall.type).getIntent();
        } else if (firstState instanceof TerminalNode node && node.getSymbol().getType() == BMLParser.Identifier) {
            currStateName = firstState.getText();
            var className = StaticJavaParser.parseClassOrInterfaceType(StringUtils.capitalize(currStateName) + "State");
            var newStateExpr = new ObjectCreationExpr(null, className, new NodeList<>());
            initBlock.addStatement(new VariableDeclarationExpr(new VariableDeclarator(new VarType(), currStateName, newStateExpr)));
            initBlock.addStatement(new MethodCallExpr(new NameExpr("namedStates"), "put", new NodeList<>(
                    new StringLiteralExpr(currStateName),
                    new NameExpr(currStateName)
            )));

            // Retrieve intent
            var nodeType = ((VariableSymbol) currentScope.resolve(node.getText())).getType();
            intent = ((BMLState) nodeType).getIntent();
        }

        // Add transition from default state
        if (ctx.parent instanceof BMLParser.DialogueBodyContext) {
            new MethodCallExpr(new NameExpr("defaultState"), "addTransition",
                    new NodeList<>(new StringLiteralExpr(intent), new NameExpr(currStateName)));
        }

        for (int i = 1, childrenSize = children.size(); i < childrenSize; i++) {
            var child = children.get(i);
            if (child instanceof BMLParser.FunctionCallContext) {

            } else if (child instanceof TerminalNode node && node.getSymbol().getType() == BMLParser.Identifier) {

            } else if (child instanceof BMLParser.TransitionInitializerContext) {
                // TODO: Unpack what is returned and add to target states of first state
                var states = visit(child);
            }
        }

        // Add body to init method
        readAndWriteClass("dialogue/DialogueAutomaton.java", "DialogueAutomaton", clazz -> {
            clazz.getMethodsByName("init").get(0).setBody(initBlock);
        });

        return super.visitAutomatonTransitions(ctx);
    }

    @Override
    public Node visitTransitionInitializer(BMLParser.TransitionInitializerContext ctx) {
        return super.visitTransitionInitializer(ctx);
    }

    private void readAndWriteClass(String path, String fileName, Consumer<ClassOrInterfaceDeclaration> c) {
        try {
            var actions = new File(botOutputPath + path);
            CompilationUnit compilationUnit = StaticJavaParser.parse(actions);

            //noinspection OptionalGetWithoutIsPresent -> We can assume that the class is present
            c.accept(compilationUnit.getClassByName("Actions").get());

            Files.write(actions.toPath(), compilationUnit.toString().getBytes());
        } catch (FileNotFoundException e) {
            throw new IllegalStateException("Could not find %s.java in %s".formatted(fileName, botOutputPath));
        } catch (IOException e) {
            throw new IllegalStateException("Error writing to file %s%s: %s".formatted(botOutputPath, path, e.getMessage()));
        }
    }

    private void writeClass(String path, String fileName) {

    }
}

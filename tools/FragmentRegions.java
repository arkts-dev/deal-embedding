import deal.ast.*;
import deal.lexer.Lexer;
import deal.ui.UiModel;
import deal.ui.UiParser;
import java.nio.file.Path;
import java.util.*;

/** Compiler-parsed facts for the documented custom regions; no generation or recovery decisions. */
public final class FragmentRegions {
    private FragmentRegions() {}
    private static String slice(String source, int start, int end) {
        return source.substring(start,end);
    }
    /** Insert nodes in the parsed root view, preserving every original UTF-16 source unit. */
    public static String appendViewNodes(String source, String nodes) {
        var module = UiParser.parseViews(Path.of("experience.dealui"),source);
        var roots = module.views().stream().filter(UiModel.View::root).toList();
        if (roots.size()!=1) throw new IllegalArgumentException("Expected one root view");
        var root=roots.getFirst();
        if (root.nodes().size()!=1 || !(root.nodes().getFirst() instanceof UiModel.Call call) || call.childBlock()==null) throw new IllegalArgumentException("Expected root container child block");
        var span = call.childBlock();
        int offset=0;
        for (int line=1;line<span.endLine();line++) {
            int next=source.indexOf('\n',offset);
            if (next<0) throw new IllegalArgumentException("Invalid root view range");
            offset=next+1;
        }
        offset+=span.endColumn()-1;
        if (offset>=source.length() || source.charAt(offset)!='}') throw new IllegalArgumentException("Invalid root view terminator");
        String result=source.substring(0,offset)+"\n"+nodes+source.substring(offset);
        UiParser.parseViews(Path.of("experience.dealui"),result);
        return result;
    }
    public static Map<String,Object> describe(String classes, String fields, String functions, String view) {
        List<Object> declared = new ArrayList<>(), imports = new ArrayList<>(), callable = new ArrayList<>(), state = new ArrayList<>();
        for (String region : List.of(classes,functions)) {
            for (StatementNode statement : FragmentSyntax.parse(region).statements()) {
                boolean exported = statement instanceof ExportDeclaration;
                StatementNode node = exported ? ((ExportDeclaration)statement).declaration() : statement;
                if (node instanceof ClassDeclaration c) {
                    declared.add(Map.of("name",c.name(),"exported",exported,"fields",c.fields().stream().map(f -> Map.of("name",f.name(),"type",FragmentSyntax.type(f.type()),"optional",f.optional())).toList()));
                } else if (node instanceof FunctionDeclaration f) {
                    callable.add(Map.of("name",f.name(),"exported",exported,"async",f.isAsync(),"result",FragmentSyntax.type(f.returnType()),"parameters",f.params().stream().map(p -> Map.of("name",p.name(),"type",FragmentSyntax.type(p.type()))).toList()));
                } else if (node instanceof ImportDeclaration i) imports.add(Map.of("alias",i.alias(),"module",i.modulePath()));
                else throw new IllegalArgumentException("Custom declaration region contains executable top-level syntax");
            }
        }
        var lexed = new Lexer(fields,"fields.fragment").tokenize();
        if (!lexed.diagnostics().isEmpty()) throw new IllegalArgumentException("Invalid field syntax");
        int depth = 0, start = 0;
        for (var token : lexed.tokens()) {
            switch (token.type()) {
                case LBRACE,LPAREN,LBRACKET -> depth++;
                case RBRACE,RPAREN,RBRACKET -> depth--;
                default -> {}
            }
            if (depth < 0) throw new IllegalArgumentException("Unbalanced field syntax");
            if (token.type() == TokenType.SEMICOLON && depth == 0) {
                String field = slice(fields,start,token.endScalarOffset()); start = token.endScalarOffset();
                String fieldSource="let " + field;
                var parsed = FragmentSyntax.parse(fieldSource).statements();
                if (parsed.size() != 1 || !(parsed.getFirst() instanceof VariableDeclaration v) || v.typeAnnotation().isEmpty()) throw new IllegalArgumentException("Expected name: Type = expression field");
                state.add(Map.of("name",v.name(),"type",FragmentSyntax.type(v.typeAnnotation().get()),"initial",FragmentSyntax.expression(v.initializer(),fieldSource)));
            }
        }
        if (depth != 0 || !slice(fields,start,fields.length()).isBlank()) throw new IllegalArgumentException("Unterminated field declaration");
        UiModel.ViewModule parsedView = UiParser.parseViews(Path.of("fragment.dealui"),view);
        return Map.of("classes",declared,"imports",imports,"functions",callable,"fields",state,
            "viewImports",parsedView.imports().stream().map(i -> Map.of("alias",i.alias(),"module",i.specifier())).toList(),
            "views",parsedView.views().stream().map(v -> Map.of("name",v.name(),"exported",v.exported(),"root",v.root(),"parameters",v.parameters().stream().map(p -> Map.of("name",p.name(),"type",p.type().name())).toList())).toList());
    }
}

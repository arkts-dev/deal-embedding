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
    private static void viewElements(UiModel.Node node, List<String> all, List<String> top, boolean direct) {
        switch (node) {
            case UiModel.Call call -> {
                all.add(call.name());
                if (direct) top.add(call.name());
                for (UiModel.Node child : call.children()) viewElements(child, all, top, false);
            }
            case UiModel.When when -> {
                for (UiModel.Node branch : when.thenNodes()) viewElements(branch, all, top, direct);
                for (UiModel.Node branch : when.elseNodes()) viewElements(branch, all, top, direct);
            }
            case UiModel.ForEach each -> {
                for (UiModel.Node child : each.children()) viewElements(child, all, top, direct);
            }
        }
    }
    private static String simple(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot+1);
    }
    /** Sections a whole view carries: every Section reachable from the root without crossing another
     *  Section, so a nested section belongs to the section that holds it (the root included) and a
     *  wrapper container cannot hide a third one. Structure decides, never the source text. */
    private static int sectionCount(UiModel.Node node) {
        return switch (node) {
            case UiModel.Call call -> simple(call.name()).equals("Section") ? 1
                    : call.children().stream().mapToInt(FragmentRegions::sectionCount).sum();
            case UiModel.When when -> when.thenNodes().stream().mapToInt(FragmentRegions::sectionCount).sum()
                    + when.elseNodes().stream().mapToInt(FragmentRegions::sectionCount).sum();
            case UiModel.ForEach each -> each.children().stream().mapToInt(FragmentRegions::sectionCount).sum();
        };
    }
    /** Every element name in a view, the element names directly under its first top-level container and
     *  the view's own section count, as compiler-parsed facts. A condition or loop at that level
     *  contributes the elements it holds, so a structural rule never has to read the source text. */
    private static Map<String,Object> viewFacts(UiModel.View view) {
        List<String> all = new ArrayList<>(), top = new ArrayList<>();
        List<UiModel.Node> nodes = view.nodes();
        int sections = 0;
        if (!nodes.isEmpty() && nodes.getFirst() instanceof UiModel.Call root) {
            all.add(root.name());
            for (UiModel.Node child : root.children()) { viewElements(child, all, top, true); }
            // The root call is counted as the section that holds the others when it is itself a Section,
            // so rooting a whole chat at Section cannot hide the section it is.
            sections += sectionCount(root);
            for (int index = 1; index < nodes.size(); index++) { viewElements(nodes.get(index), all, top, false); sections += sectionCount(nodes.get(index)); }
        } else {
            for (UiModel.Node node : nodes) { viewElements(node, all, top, true); sections += sectionCount(node); }
        }
        return Map.of("name",view.name(),"exported",view.exported(),"root",view.root(),"elements",all,"children",top,"sections",sections,
            "parameters",view.parameters().stream().map(p -> Map.of("name",p.name(),"type",p.type().name())).toList());
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
            "views",parsedView.views().stream().map(FragmentRegions::viewFacts).toList());
    }
}

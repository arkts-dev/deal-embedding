import deal.ui.*;
import java.nio.file.Path;
import java.util.*;

/** Compiler-parsed authored view trees; native adapters bind lexical ports, never replace text. */
public final class FragmentStructure {
    private FragmentStructure() {}
    private static final Map<String,UiModel.View> VIEWS=initialize();
    private static Map<String,UiModel.View> initialize() {
        var views=new LinkedHashMap<String,UiModel.View>();
        for(var source:List.of(AuthoredFragments.INPUT_VIEW,AuthoredFragments.RESULT_VIEW,AuthoredFragments.COMPARISON_VIEW,AuthoredFragments.CHOICE_VIEW,AuthoredFragments.ALLOCATION_VIEW,AuthoredFragments.QUOTES_VIEW,AuthoredFragments.WORKSPACE_VIEW)) {
            var module=UiParser.parseViews(Path.of("fragment.dealui"),source);
            for(var view:module.views()) {
                if(views.put(view.name(),view)!=null)throw new IllegalArgumentException("Duplicate authored view");
            }
        }
        return Collections.unmodifiableMap(views);
    }
    public static List<UiModel.Node> viewNodes(String kind) {
        var view=VIEWS.get(kind);
        if(view==null)throw new IllegalArgumentException("Unknown authored fragment view " + kind);
        return view.nodes();
    }
}

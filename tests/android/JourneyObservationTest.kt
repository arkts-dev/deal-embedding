package dev.deal.cutover.tests

import dev.deal.embedding.*
import org.json.*

/**
 * The journey time evidence, proven without a live provider: the exact interpretation rules the opt-in
 * acceptance journeys rely on to prove the disclosed 16:00-17:00 collection window, the before-18:30
 * delivery arrival and a departure instant that belongs to the previous day, the shapes they must
 * refuse instead of reporting as proof, and the outcome each verdict produces (only a contradiction
 * fails; no usable evidence blocks the journey).
 */
internal fun checkJourneyObservation() {
    fun string(name:String,value:String)=JSONObject().put("name",name).put("kind","string").put("stringValue",value)
    fun number(name:String,value:Double)=JSONObject().put("name",name).put("kind","number").put("numberValue",value)
    fun node(component:String,props:List<JSONObject>)=JSONObject().put("component",component).put("props",JSONArray(props)).put("children",JSONArray())
    fun column(children:List<JSONObject>)=JSONObject().put("component","ui.Column").put("props",JSONArray()).put("children",JSONArray(children))
    fun typed(component:String,prop:String,marker:String,value:Double)=column(listOf(node(component,listOf(string("label",marker),number(prop,value)))))
    fun spelled(vararg texts:String)=column(texts.map{node("ui.Text",listOf(string("text",it)))})

    // A contradiction fails the journey; no usable evidence blocks it and never reports it checked.
    check(TimeEvidence.Proven.outcome==TimeOutcome.Proven){"proven evidence needs no blocker"}
    check(TimeEvidence.Contradiction("late").outcome==TimeOutcome.Contradicted){"a published contradiction is a located failure"}
    check(TimeEvidence.Unproven("absent").outcome==TimeOutcome.Unproven){"absent evidence is a located blocker"}
    check(TimeEvidence.Unproven("absent").detail=="absent" && TimeEvidence.Proven.detail.isEmpty()){"the blocker carries its located reason"}

    // A minute count and the epoch instant of the task date are times of the task day. A duration,
    // seconds of the day, an epoch day, a fractional count and a value outside the day are not, so none
    // of them may prove an arrival that is actually a countdown or a different quantity.
    check(JourneyTimes.minuteOfDay(1035.0)==1035.0){"an integral minute count is a time of the task day"}
    check(JourneyTimes.minuteOfDay(0.0)==0.0){"midnight is a time of the task day"}
    check(JourneyTimes.minuteOfDay(fixtureEpochMillis(1035.0))==1035.0){"the epoch instant of the task date is a time of the task day"}
    for(value in listOf(1800000.0,68400.0,fixtureEpochDay(),1440.0,-5.0,1035.5)) {
        check(JourneyTimes.minuteOfDay(value)==null){"$value is no time of the task day and must not prove an arrival"}
    }

    // The published denominations one disclosed minute accepts, and one it does not.
    check(JourneyTimes.missingTimes(listOf(960.0,1020.0),listOf(960.0,1020.0)).isEmpty()){"both disclosed minutes are published"}
    check(JourneyTimes.missingTimes(listOf(960.0,1020.0),listOf(960.0))==listOf(1020.0)){"the missing end is reported"}
    check(JourneyTimes.missingTimes(listOf(960.0),listOf(960.0*60000.0)).isEmpty()){"day milliseconds denote the disclosed minute"}
    check(JourneyTimes.missingTimes(listOf(1020.0),listOf(fixtureEpochMillis(1020.0))).isEmpty()){"the epoch instant denotes the disclosed minute"}
    check(JourneyTimes.missingTimes(listOf(1020.0),listOf(960.0))==listOf(1020.0)){"another minute never stands in for one end"}

    // The window: both ends typed or both disclosed: a single end or a wrong end is no proof of the
    // interval, and an unrelated quantity is never read as its boundary.
    val window=listOf("window","from","until","start","end")
    val ends=listOf(960.0,1020.0)
    val spelling=listOf("16:00","17:00")
    fun windowTree(vararg entries:Pair<String,Double>)=column(entries.map{(marker,value)->node("ui.TimeField",listOf(string("label",marker),number("value",value)))})
    check(JourneyTimes.window(windowTree("From" to 960.0,"Until" to 1020.0),window,ends,spelling)==TimeEvidence.Proven){"both typed ends prove the disclosed interval"}
    check(JourneyTimes.window(windowTree("Window start" to 960.0,"Window end" to 1020.0),window,ends,spelling)==TimeEvidence.Proven){"the declared denominations of a typed end are accepted"}
    check(JourneyTimes.window(windowTree("From" to 960.0*60000.0,"Until" to fixtureEpochMillis(1020.0)),window,ends,spelling)==TimeEvidence.Proven){"day milliseconds and the epoch instant denote the disclosed ends"}
    check(JourneyTimes.window(windowTree("From" to 960.0),window,ends,spelling) is TimeEvidence.Contradiction){"a window that drops the disclosed end contradicts it"}
    check(JourneyTimes.window(windowTree("From" to 960.0,"Until" to 1000.0),window,ends,spelling) is TimeEvidence.Contradiction){"a window end other than the disclosed one contradicts it"}
    check(JourneyTimes.window(column(listOf(node("ui.TimeField",listOf(string("label","From"),number("value",960.0))),node("ui.TimeField",listOf(string("label","Until"),number("value",1000.0))),node("ui.Text",listOf(string("text","16:00-17:00"))))),window,ends,spelling) is TimeEvidence.Contradiction){"a stated window end other than the disclosed one contradicts the interval even when the caption repeats it"}
    check(JourneyTimes.window(column(listOf(node("ui.TimeField",listOf(string("label","Start"),number("value",960.0))),node("ui.TimeField",listOf(string("label","Estimated end"),number("value",1065.0))),node("ui.Text",listOf(string("text","16:00-17:00"))))),window,ends,spelling)==TimeEvidence.Proven){"an incidental start/end instant is no window boundary, so the disclosed caption still proves the interval"}
    check(JourneyTimes.window(spelled("16:00-17:00"),window,ends,spelling)==TimeEvidence.Proven){"the disclosed spelling of both ends proves the interval"}
    check(JourneyTimes.window(spelled("16:00"),window,ends,spelling) is TimeEvidence.Unproven){"one published end is not the interval"}
    check(JourneyTimes.window(spelled("Collection at The Foundry"),window,ends,spelling) is TimeEvidence.Unproven){"an unrelated caption proves no window"}
    check(JourneyTimes.window(column(listOf(node("ui.NumberText",listOf(string("label","Travel time"),number("value",29.0))),node("ui.Text",listOf(string("text","16:00-17:00"))))),window,ends,spelling)==TimeEvidence.Proven){"an unrelated duration is no window boundary, so the disclosed caption still proves the interval"}
    check(JourneyTimes.window(typed("ui.NumberText","value","Travel time",29.0),window,ends,spelling) is TimeEvidence.Unproven){"a duration is never read as a window boundary"}
    check(JourneyTimes.window(typed("ui.NumberField","value","Hire period",375.0),window,ends,spelling) is TimeEvidence.Unproven){"a period duration is never read as a window boundary"}
    check(JourneyTimes.window(typed("ui.NumberField","value","Start buffer",15.0),window,ends,spelling) is TimeEvidence.Unproven){"a buffer named with a boundary word is excluded, so it is no boundary"}
    check(JourneyTimes.window(typed("ui.NumberField","value","Unloading before start",15.0),window,ends,spelling) is TimeEvidence.Unproven){"a buffer control is never read as a window boundary"}
    check(JourneyTimes.window(typed("ui.DateField","value","Collect from",fixtureEpochDay()),window,ends,spelling) is TimeEvidence.Unproven){"an epoch day is never read as a window boundary"}
    check(JourneyTimes.window(typed("ui.TimeField","value","Collect from",1035.0),window,ends,spelling) is TimeEvidence.Contradiction){"a stated collection start other than the disclosed one contradicts the interval"}
    check(JourneyTimes.window(typed("ui.NumberField","value","Weekend",1020.0),window,ends,spelling) is TimeEvidence.Unproven){"a word that merely contains `end` is no boundary"}
    check(JourneyTimes.window(windowTree("Start" to 960.0),window,ends,spelling) is TimeEvidence.Unproven){"a lone start instant is no window boundary, so it leaves the interval unproven and the journey blocks instead of failing the view"}
    check(JourneyTimes.window(windowTree("Start" to 960.0,"Estimated end" to 1020.0),window,ends,spelling)==TimeEvidence.Proven){"start and end instants that agree with the disclosed ends prove the interval"}
    check(JourneyTimes.window(windowTree("Start" to 960.0,"Estimated end" to 1065.0),window,ends,spelling) is TimeEvidence.Unproven){"start and end instants that agree with no disclosed end never contradict the interval they do not describe, so it stays unproven"}
    check(JourneyTimes.window(column(listOf(node("ui.TimeField",listOf(string("label","Start"),number("value",960.0))),node("ui.Text",listOf(string("text","16:00-17:00"))))),window,ends,spelling)==TimeEvidence.Proven){"the disclosed caption proves the interval beside a lone start instant"}
    check(JourneyTimes.window(column(listOf(node("ui.TimeField",listOf(string("label","From"),number("value",960.0))),node("ui.Text",listOf(string("text","16:00-17:00"))))),window,ends,spelling)==TimeEvidence.Proven){"a disclosed caption proves the interval beside one typed end"}

    // Rollover: an instant that belongs to the previous task day keeps its own clock reading on that
    // day. A departure at -34 minutes is 23:26 of the previous day, never 23:26 of the task day.
    val departureMarkers=listOf("departure")
    fun rollover(component:String,prop:String,marker:String,value:Double)=JourneyTimes.rollover(typed(component,prop,marker,value),departureMarkers,-34.0)
    check(rollover("ui.NumberText","value","Departure",fixtureEpochMillis(-34.0))==TimeEvidence.Proven){"the previous day's epoch instant proves a rollover departure"}
    check(rollover("ui.NumberText","value","Departure",-34.0)==TimeEvidence.Proven){"a negative minute count proves a rollover departure"}
    check(rollover("ui.NumberText","value","Departure",-34.0*60000.0)==TimeEvidence.Proven){"the day-millisecond magnitude of the negative count proves it as well"}
    check(rollover("ui.NumberText","value","Departure",1406.0) is TimeEvidence.Contradiction){"a same-day wrap of the same clock reading contradicts the preserved instant"}
    check(rollover("ui.NumberText","value","Departure",fixtureEpochMillis(1406.0)) is TimeEvidence.Contradiction){"the same-day epoch instant contradicts the preserved instant"}
    check(rollover("ui.NumberText","value","Departure",1406.0*60000.0) is TimeEvidence.Contradiction){"the same-day day-millisecond spelling contradicts it too"}
    check(rollover("ui.NumberField","value","Departure buffer",15.0) is TimeEvidence.Unproven){"a buffer value is never read as the departure instant"}
    check(JourneyTimes.rollover(column(emptyList()),departureMarkers,-34.0) is TimeEvidence.Unproven){"no published instant leaves the rollover unproven, so the journey blocks"}
    check(JourneyTimes.rollover(typed("ui.NumberText","value","Travel time",1740.0),departureMarkers,-34.0) is TimeEvidence.Unproven){"a duration under another label never stands in for the departure instant"}

    // The delivery arrival: before 18:30 (1110 minutes of the task day) or nothing. The arrival label
    // must be on the value's own node; a quantity under an unrelated or inherited label is no arrival.
    val arrivals=FOOD_ARRIVALS
    val deadline=1110.0
    fun arrival(component:String,prop:String,marker:String,value:Double)=JourneyTimes.arrival(typed(component,prop,marker,value),arrivals,deadline)
    check(arrival("ui.ClockText","minutes","Arrival",1035.0)==TimeEvidence.Proven){"an arrival at 17:15 is before 18:30"}
    check(arrival("ui.Timer","millis","Estimated arrival",fixtureEpochMillis(1080.0))==TimeEvidence.Proven){"the epoch instant of the task date proves the arrival"}
    check(arrival("ui.NumberField","value","Delivery time",1000.0)==TimeEvidence.Proven){"an editable delivery time is a declared instant"}
    check(arrival("ui.ClockText","minutes","Arrival",1140.0) is TimeEvidence.Contradiction){"an arrival at 19:00 is not before 18:30"}
    check(arrival("ui.ClockText","minutes","Arrival",1110.0) is TimeEvidence.Contradiction){"an arrival at exactly 18:30 is not before 18:30"}
    check(arrival("ui.Timer","millis","Estimated arrival",fixtureEpochMillis(1500.0)) is TimeEvidence.Contradiction){"an arrival on the next day contradicts the task date"}
    check(arrival("ui.ClockText","minutes","Arrival",1800000.0) is TimeEvidence.Unproven){"a 30 minute duration proves no arrival"}
    check(arrival("ui.ClockText","minutes","Arrival",68400.0) is TimeEvidence.Unproven){"seconds of the day prove no arrival"}
    check(arrival("ui.DateField","value","Arrival",fixtureEpochDay()) is TimeEvidence.Unproven){"an epoch day proves a date, never an instant"}
    check(arrival("ui.NumberText","value","Estimated delivery total",1035.0) is TimeEvidence.Unproven){"a delivery price named as a delivery time is excluded, so it proves no arrival at 17:15"}
    check(arrival("ui.NumberText","value","Vegetarian pizza",950.0) is TimeEvidence.Unproven){"`eta` matches whole words only, so a vegetarian row is no arrival"}
    check(arrival("ui.NumberText","value","ETA",1035.0)==TimeEvidence.Proven){"a typed ETA is a declared arrival instant"}
    check(arrival("ui.NumberText","value","Arrival in 25 min",25.0) is TimeEvidence.Unproven){"a countdown is not an arrival instant"}
    check(arrival("ui.NumberText","value","In 25 min (arrival)",25.0) is TimeEvidence.Unproven){"a leading countdown word is excluded all the same"}
    check(arrival("ui.Timer","millis","Arrival",900.0) is TimeEvidence.Unproven){"a Timer millis below the task date is no instant of the day"}
    check(arrival("ui.ClockText","minutes","Arrival by 18:30",1140.0) is TimeEvidence.Unproven){"the disclosed deadline itself is not the arrival"}
    check(JourneyTimes.arrival(column(listOf(node("ui.Text",listOf(string("text","Delivery time"))),node("ui.NumberText",listOf(number("value",900.0))))),arrivals,deadline) is TimeEvidence.Unproven){"an inherited sibling label never turns a quantity into an arrival"}

    // A required item and each required attribute of it are named by one item node: the item word is on
    // that node's own props (a heading that groups the item is not the item), the attributes may be on the
    // item node or its descendants, and every marker is a whole word, so `male` is never satisfied by
    // `female` and an attribute published for another item or another node never proves the requirement.
    fun items(vararg texts:String)=column(texts.map{node("ui.Text",listOf(string("text",it)))})
    check(JourneyTimes.namedItem(items("Adjustable boom stand with clip adapter"),"boom",listOf("adjustable"))!=null){"the adjustable boom stand is named"}
    check(JourneyTimes.namedItem(items("Boom stand"),"boom",listOf("adjustable"))==null){"a stand that never says adjustable leaves the required attribute unproven"}
    check(JourneyTimes.namedItem(items("Balanced XLR female to male cable, 10 m"),"xlr",listOf("balanced","female","male"))!=null){"one balanced XLR female-to-male cable names every required attribute"}
    check(JourneyTimes.namedItem(items("XLR female cable"),"xlr",listOf("male"))==null){"`male` is not satisfied by `female`"}
    check(JourneyTimes.namedItem(items("Boom stand","Adjustable height"),"boom",listOf("adjustable"))==null){"an attribute published for another item never proves the requirement"}
    fun detail(label:String,text:String)=node("ui.Text",listOf(string("label",label),string("detail",text)))
    check(JourneyTimes.namedItem(column(listOf(detail("Balanced XLR cable","female to male, 10 m"))),"xlr",listOf("balanced","male"))!=null){"the label and detail of one node name the item and its attributes together"}
    fun row(label:String,vararg children:JSONObject)=JSONObject().put("component","ui.Row").put("props",JSONArray(listOf<JSONObject>(string("label",label)))).put("children",JSONArray(children.toList()))
    fun plain(component:String,vararg children:JSONObject)=JSONObject().put("component",component).put("props",JSONArray(emptyList<JSONObject>())).put("children",JSONArray(children.toList()))
    check(JourneyTimes.namedItem(row("XLR cable",node("ui.Text",listOf(string("detail","balanced female to male")))),"xlr",listOf("balanced","female","male"))!=null){"an item row names its cable together with its connector detail"}
    check(JourneyTimes.namedItem(row("Balanced XLR cable",node("ui.Text",listOf(string("text","female to male, 10 m")))),"xlr",listOf("balanced","female","male"))!=null){"a cable row names its label together with its connector detail"}
    check(JourneyTimes.namedItem(row("Balanced XLR cable",node("ui.Text",listOf(string("detail","XLR connectors: female to male")))),"xlr",listOf("balanced","female","male"))!=null){"a detail that repeats the item word still belongs to its cable row"}
    check(JourneyTimes.namedItem(row("XLR cable",plain("ui.Column",node("ui.Text",listOf(string("text","balanced female to male"))))),"xlr",listOf("balanced","female","male"))!=null){"one wrapper around a single detail still belongs to its cable"}
    check(JourneyTimes.namedItem(items("Balanced XLR cable","XLR male cable"),"xlr",listOf("balanced","male"))==null){"two differently named cables never become one balanced female-to-male cable"}
    check(JourneyTimes.namedItem(items("XLR female, 10 m","XLR male, 10 m"),"xlr",listOf("balanced","female","male"))==null){"two separately named connectors never become one balanced female-to-male cable"}
    check(JourneyTimes.namedItem(column(listOf(row("Balanced XLR cables",node("ui.Text",listOf(string("text","XLR female, 10 m"))),node("ui.Text",listOf(string("text","XLR male, 10 m")))))),"xlr",listOf("balanced","female","male"))==null){"a heading that names the group of cables never turns two of them into one cable"}
    check(JourneyTimes.namedItem(row("Balanced XLR cables",node("ui.Text",listOf(string("text","female, 10 m"))),node("ui.Text",listOf(string("text","male, 10 m")))),"xlr",listOf("balanced","female","male"))==null){"one heading over two unnamed branches never merges them into one cable"}
    check(JourneyTimes.namedItem(row("Balanced XLR cables",plain("ui.Column",node("ui.Text",listOf(string("text","female, 10 m"))),node("ui.Text",listOf(string("text","male, 10 m"))))),"xlr",listOf("balanced","female","male"))==null){"one wrapper between two descriptions still keeps them two cables"}

    // The length and other values one required item publishes are the ones inside its own row, or beside it
    // in that row; a spare cable naming the same thing never supplies them, and an item the view never named
    // publishes none.
    fun length(value:Double,unit:String)=node("ui.NumberText",listOf(number("value",value),string("suffix",unit)))
    val requiredCable=row("XLR cable",length(10.0," m"))
    check(JourneyTimes.itemValues(requiredCable,requiredCable,"xlr",listOf("xlr","cable")).map{it.second}==listOf(10.0)){"a length inside the required cable row belongs to it"}
    val cableName=node("ui.Text",listOf(string("label","XLR cable")))
    check(JourneyTimes.itemValues(row("Item",cableName,length(10.0," m")),cableName,"xlr",listOf("xlr","cable")).map{it.second}==listOf(10.0)){"a length beside the required cable in its row belongs to it"}
    val spare=row("XLR cable spare",length(20.0," m"))
    check(JourneyTimes.itemValues(column(listOf(requiredCable,spare)),requiredCable,"xlr",listOf("xlr","cable")).map{it.second}==listOf(10.0)){"a spare cable in the same list never supplies the required cable's length"}
    fun price(label:String,value:Double)=node("ui.NumberText",listOf(string("label",label),number("value",value),string("suffix"," EUR")))
    val priced=row("XLR cable",length(10.0," m"),price("XLR cable hire",12.0))
    check(JourneyTimes.itemValues(priced,priced,"xlr",listOf("xlr","cable")).map{it.second}==listOf(10.0)){"the length inside the cable row is read beside its own price, and the price is no length"}
    val bare=row("XLR cable")
    val siblingRow=row("patch cable",node("ui.NumberText",listOf(string("label","Cable length"),number("value",3.0),string("suffix"," m"))))
    check(JourneyTimes.itemValues(column(listOf(bare,siblingRow)),bare,"xlr",listOf("xlr","cable")).isEmpty()){"a sibling row's length is not the required cable's, so a cable row without a length publishes none"}
    val ownRowed=row("XLR cable",length(10.0," m"))
    check(JourneyTimes.itemValues(column(listOf(ownRowed,siblingRow)),ownRowed,"xlr",listOf("xlr","cable")).map{it.second}==listOf(10.0)){"a sibling row's shorter length never vetoes the required cable's own length"}
    check(JourneyTimes.itemValues(column(listOf(spare)),null,"xlr",listOf("xlr","cable")).isEmpty()){"an item the view never named publishes no length"}

    // The stale state a dependent value is displayed with: its own labels, an ancestor's, or a marker of
    // its own section. A marker published for another section never stales this value, so an unrelated
    // banner can never stand in for the withdrawal a changed argument must cause.
    fun value(label:String,number:Double)=node("ui.NumberText",listOf(string("label",label),number("value",number)))
    fun marker(text:String)=node("ui.Text",listOf(string("text",text)))
    val total=value("Package total",450.0)
    check(ViewTree.staledValue(column(listOf(marker("Stale - reload the estimate"),total)),total)!=null){"a section banner above the value stales it"}
    check(ViewTree.staledValue(column(listOf(total,marker("Stale - reload the quote"))),total)!=null){"a section banner below the value stales it too"}
    val own=value("Stale package total",450.0)
    check(ViewTree.staledValue(column(listOf(own)),own)!=null){"the value's own label carries the stale state"}
    check(ViewTree.staledValue(column(listOf(total,column(listOf(marker("Stale - other section"))))),total)==null){"a banner of another section never stales this value"}
    fun staledSibling(label:String,number:Double,word:String)=node("ui.NumberStat",listOf(string("label",label),number("value",number),string("supporting",word)))
    check(ViewTree.staledValue(column(listOf(total,staledSibling("Travel time",29.0,"stale"))),total)==null){"a sibling typed value's stale label describes that value, never this one"}
    check(ViewTree.staledValue(column(listOf(total,node("ui.Stat",listOf(string("label","Travel time"),string("value","29 min"),string("supporting","stale"))))),total)==null){"a sibling text value's stale label describes that value, never this one"}
    check(ViewTree.staledValue(column(listOf(total,node("ui.SectionHeader",listOf(string("title","Stock availability"),string("subtitle","stale"))))),total)==null){"a heading that follows the value heads the next section, never this one"}
    val ownStated=staledSibling("Package total",450.0,"stale")
    check(ViewTree.staledValue(column(listOf(ownStated)),ownStated,ViewTree.WITHDRAWN_WORDS)!=null){"the value's own supporting state stales it"}
    check(ViewTree.staledValue(column(listOf(marker("Estimate unavailable"),total)),total,ViewTree.WITHDRAWN_WORDS)!=null){"an unavailable banner withdraws the value it describes"}
    check(ViewTree.staledValue(column(listOf(node("ui.InsetBanner",listOf(string("title","Estimate unavailable"))),total)),total,ViewTree.WITHDRAWN_WORDS)!=null){"a banner component with its own action still discloses the withdrawal"}
    fun section(title:String,vararg children:JSONObject)=JSONObject().put("component","ui.Section").put("props",JSONArray(listOf<JSONObject>(string("title",title)))).put("children",JSONArray(children.toList()))
    check(ViewTree.staledValue(section("Estimate unavailable",total),total,ViewTree.WITHDRAWN_WORDS)!=null){"the value's containing section discloses the withdrawal in its own title"}
    check(ViewTree.staledValue(column(listOf(marker("Estimate unavailable"),total)),total)==null){"the stale word set of the pending-as-stale proof is not widened by the withdrawal words"}
    check(ViewTree.staledValue(column(listOf(total)),total)==null){"a value in a section with no marker is current"}
}

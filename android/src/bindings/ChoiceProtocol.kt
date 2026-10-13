package dev.deal.embedding

import org.json.*
import kotlin.math.abs

/** Generic answer validation. No domain eligibility, confidence threshold or provider fallback. */
internal object ChoiceProtocol {
    fun questions(questions: JSONArray): JSONObject {
        require(questions.length() in 1..32)
        val aliases=JSONObject();val decisions=JSONObject()
        for(i in 0 until questions.length()) {
            val q=questions.getJSONObject(i);val name=q.getString("name");val instructions=q.getString("instructions");val options=q.getJSONArray("options")
            require(name.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) && !aliases.has(name) && instructions.length in 1..4096 && options.length() in 1..255)
            val criteria=JSONObject();val names=JSONArray()
            for(j in 0 until options.length()){val o=options.getJSONObject(j);val n=o.getString("name");val d=o.getString("description");require(n.isNotBlank()&&n.length<=64&&!criteria.has(n)&&d.length in 1..4096);criteria.put(n,d);names.put(n)}
            aliases.put(name,names);decisions.put(name,JSONObject().put("type","choice").put("instructions",instructions).put("criteria",criteria))
        }
        return JSONObject().put("questions",aliases).put("decisionQuestions",decisions)
    }
    fun answers(raw: String, questions: JSONArray): JSONObject {
        val issued=questions(questions).getJSONObject("questions")
        fun rejected(reason: String)=JSONObject().put("accepted",false).put("answers",JSONArray()).put("reason",reason)
        val parsed=try{JSONObject(raw)}catch(e:JSONException){return rejected("CHOICE_JSON")}
        if(parsed.keys().asSequence().any{it !in setOf("answers","typedAnswers")})return rejected("CHOICE_SHAPE")
        val typed=parsed.optJSONArray("typedAnswers");val selected=parsed.optJSONObject("answers") ?: return rejected("CHOICE_SHAPE")
        if(typed!=null&&(0 until typed.length()).any{typed.optJSONObject(it)?.let{a->a.opt("question") !is String||a.opt("choice") !is String}?:true})return rejected("CHOICE_SHAPE")
        if(parsed.has("typedAnswers")&&(typed==null||typed.length()!=issued.length()||(0 until typed.length()).any{typed.optJSONObject(it)==null}))return rejected("CHOICE_SHAPE")
        if(selected.length()!=issued.length()||selected.keys().asSequence().any{!issued.has(it)})return rejected("CHOICE_SHAPE")
        val answers=JSONArray()
        for(name in issued.keys()) {
            val choice=selected.opt(name);val options=issued.getJSONArray(name)
            if(choice !is String || (0 until options.length()).none{options.getString(it)==choice})return rejected("CHOICE_ALIAS")
            val answer=if(typed!=null)(0 until typed.length()).map{typed.getJSONObject(it)}.singleOrNull{it.optString("question")==name} ?: return rejected("CHOICE_SHAPE") else JSONObject().put("question",name).put("choice",choice).put("probabilities",JSONArray()).put("confidence",JSONObject.NULL)
            if(answer.optString("choice")!=choice||answer.keys().asSequence().any{it !in setOf("question","choice","probabilities","confidence")})return rejected("CHOICE_SHAPE")
            val ps=answer.optJSONArray("probabilities") ?: return rejected("CHOICE_SHAPE")
            var sum=0.0;val seen=mutableSetOf<String>()
            for(i in 0 until ps.length()){val p=ps.optJSONObject(i) ?: return rejected("CHOICE_SHAPE");val option=p.opt("option");if(option !is String||p.length()!=2)return rejected("CHOICE_SHAPE");val value=p.opt("probability");if(value !is Number || !value.toDouble().isFinite()||value.toDouble() !in 0.0..1.0||!seen.add(option)||(0 until options.length()).none{options.getString(it)==option})return rejected("CHOICE_PROBABILITY");sum+=value.toDouble()}
            if(ps.length()>0&&(seen.size!=options.length()||abs(sum-1)>ps.length()*0.005+1e-9))return rejected("CHOICE_PROBABILITY")
            val confidence=answer.opt("confidence");if(confidence!=null&&confidence!==JSONObject.NULL&&(confidence !is Number||!confidence.toDouble().isFinite()||confidence.toDouble() !in 0.0..1.0))return rejected("CHOICE_CONFIDENCE")
            answers.put(answer)
        }
        return JSONObject().put("accepted",true).put("answers",answers).put("reason","")
    }
}

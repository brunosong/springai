package com.brunosong.springai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class TodoTools {

    private final List<String> todos = new CopyOnWriteArrayList<>();
    private final Map<String, List<String>> todosByUser = new ConcurrentHashMap<>();

    @Tool(description = "할 일 목록에서 항목을 삭제한다")
    public String removeTodo(@ToolParam(description = "삭제할 할 일. 목록에 있는 문구 그대로") String item) {
        log.info("[tool] removeTodo({})", item);
        return todos.remove(item) ? "삭제됨: " + item : "없는 항목: " + item;
    }

    @Tool(description = "내 할 일 목록에 항목을 추가한다")
    public String addMyTodo(@ToolParam(description = "추가할 할 일") String item, ToolContext toolContext) {
        String userId = (String) toolContext.getContext().get("userId");
        log.info("[tool] addMyTodo(user={}, item={})", userId, item);
        todosByUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(item);
        return "추가됨";
    }

    @Tool(description = "내 할 일 목록을 조회한다")
    public List<String> listMyTodos(ToolContext toolContext) {
        String userId = (String) toolContext.getContext().get("userId");
        log.info("[tool] listMyTodos(user={})", userId);
        return todosByUser.getOrDefault(userId, List.of());
    }

    @Tool(description = "할 일 개수를 샌다", returnDirect = true)
    public String countTodos() {
        return "할 일은 총 " + todos.size() + "개입니다.";
    }

//    @Tool(description = "할 일 목록에 항목을 추가한다")
//    public String addTodo(@ToolParam(description = "추가할 할 일") String item) {
//        log.info("[tool] addTool({})", item);
//        todos.add(item);
//        return "추가됨: " + item;
//    }
//
//    @Tool(description = "현재 할 일 목록 전체를 조회한다")
//    public List<String> listTodos() {
//        log.info("[tool] listTodos()");
//        return todos;
//    }
}

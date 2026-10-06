package com.teriteri.backend.comment;

import com.teriteri.backend.service.comment.CommentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

// @SpringBootTest(properties = {"spring.profiles.active=test"})
public class TestComment {
    @Autowired
    private CommentService commentService;

    @Test
    public void testAddComment() {
        Integer vid = 1;
        Integer uid = 1;
        boolean isRoot = true;
        Integer fatherId = null;
        Integer toUserId = null;
        String content = "这是第一条根级评论";
//        boolean result = commentService.sendComment(vid, uid, isRoot, fatherId, toUserId, content);
//        System.out.println(result);
    }

    @Test
    public void test(){
        String s = " " == null ? null:" ";
        System.out.println("1" + s + "1");
    }

    @Test
    public void test1(){
        int a = 189;
        int b = 189;
        String a1 = "a";
        String a2 = "a";
        System.out.println(a == b);
        System.out.println(a1 == a2);
        System.out.println(a1.equals(a2));
    }


}

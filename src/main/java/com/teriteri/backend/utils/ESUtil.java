package com.teriteri.backend.utils;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.teriteri.backend.pojo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@Slf4j
public class ESUtil {
    // 
    @Autowired
    private ElasticsearchClient client;

    /**
     * 添加视频文档
     * @param video
     */
    public void addVideo(Video video) throws IOException {
        try {
            ESVideo esVideo = new ESVideo(video.getVid(), video.getUid(), video.getTitle(), video.getMcId(), video.getScId(), video.getTags(), video.getStatus());
            client.index(i -> i.index("video").id(esVideo.getVid().toString()).document(esVideo));
        } catch (IOException e) {
            log.error("添加视频文档到ElasticSearch时出错了：" + e);
            throw e;
        }
    }

    /**
     * 删除视频文档
     * @param vid
     */
    public void deleteVideo(Integer vid) throws IOException {
        try {
            client.delete(d -> d.index("video").id(vid.toString()));
        } catch (IOException e) {
            log.error("删除ElasticSearch视频文档时失败了：" + e);
            throw e;
        } catch (ElasticsearchException e) {
            // 文档本就不存在（ES 曾经不可用 / 索引漏建留下的历史欠账）时，删除对它而言等价于成功。
            // 不放过 404 的话，用户会因为一条 ES 里根本没有的稿件而永远撤销不掉，
            // 且异常会连带整个事务回滚。删除是幂等操作，404 直接放行。
            if (e.status() == 404) {
                log.warn("ES 中视频 {} 的文档本就不存在，跳过删除", vid);
                return;
            }
            log.error("删除ElasticSearch视频文档时失败了：" + e);
            throw e;
        }
    }

    /**
     * 更新视频文档
     * @param video
     */
    public void updateVideo(Video video) throws IOException {
        try {
            ESVideo esVideo = new ESVideo(video.getVid(), video.getUid(), video.getTitle(), video.getMcId(), video.getScId(), video.getTags(), video.getStatus());
            // docAsUpsert：文档不存在时（如索引漏建、ES 曾不可用）直接按完整文档写入，避免 document_missing_exception 导致审核失败
            client.update(u -> u.index("video").id(video.getVid().toString()).doc(esVideo).docAsUpsert(true), ESVideo.class);
        } catch (IOException e) {
            log.error("更新ElasticSearch视频文档时出错了：" + e);
            throw e;
        }
    }

    /**
     * 查询相关数据数量
     * @param keyword
     * @param onlyPass  是否只查询过审的
     * @return
     */
    public Long getVideoCount(String keyword, boolean onlyPass) {
        try {
            Query query = Query.of(q -> q.multiMatch(m -> m.fields("title", "tags").query(keyword)));
            Query query1 = Query.of(q -> q.constantScore(c -> c.filter(f -> f.term(t -> t.field("status").value(1)))));
            Query bool = Query.of(q -> q.bool(b -> b.must(query1).must(query)));
            CountRequest countRequest;
            if (onlyPass) {
                countRequest = new CountRequest.Builder().index("video").query(bool).build();
            } else {
                countRequest = new CountRequest.Builder().index("video").query(query).build();
            }
            CountResponse countResponse = client.count(countRequest);
            return countResponse.count();
        } catch (IOException e) {
            log.error("查询ES相关视频数量时出错了：" + e);
            return 0L;
        }
    }

    /**
     * 模糊匹配，分页查询
     * @param keyword   查询关键词
     * @param page  第几页 从1开始
     * @param size  每页查多少条数据 一般30条
     * @return 包含查到的数据id列表，按匹配分数排序
     */
    public List<Integer> searchVideosByKeyword(String keyword, Integer page, Integer size, boolean onlyPass) {
        try {
            List<Integer> list = new ArrayList<>();
            Query query = Query.of(q -> q.multiMatch(m -> m.fields("title", "tags").query(keyword)));
            Query query1 = Query.of(q -> q.constantScore(c -> c.filter(f -> f.term(t -> t.field("status").value(1)))));
            Query bool = Query.of(q -> q.bool(b -> b.must(query1).must(query)));
            SearchRequest searchRequest;
            if (onlyPass) {
                searchRequest = new SearchRequest.Builder().index("video").query(bool).from((page - 1) * size).size(size).build();
            } else {
                searchRequest = new SearchRequest.Builder().index("video").query(query).from((page - 1) * size).size(size).build();
            }
            SearchResponse<ESVideo> searchResponse = client.search(searchRequest, ESVideo.class);
            for (Hit<ESVideo> hit : searchResponse.hits().hits()) {
                list.add(hit.source().getVid());
            }
            return list;
        } catch (IOException e) {
            log.error("查询ES相关视频文档时出错了：" + e);
            return Collections.emptyList();
        }
    }


    /**
     * 添加用户文档
     * @param user
     */
    public void addUser(User user) throws IOException {
        try {
            ESUser esUser = new ESUser(user.getUid(), user.getNickname());
            client.index(i -> i.index("user").id(esUser.getUid().toString()).document(esUser));
        } catch (IOException e) {
            log.error("添加用户文档到elasticsearch时出错了：" + e);
            throw e;
        }
    }

    /**
     * 删除视频文档
     * @param uid
     */
    public void deleteUser(Integer uid) throws IOException {
        try {
            client.delete(d -> d.index("user").id(uid.toString()));
        } catch (IOException e) {
            log.error("删除ElasticSearch用户文档时失败了：" + e);
            throw e;
        }
    }

    /**
     * 更新用户文档
     * @param user
     */
    public void updateUser(User user) throws IOException {
        try {
            ESUser esUser = new ESUser(user.getUid(), user.getNickname());
            // 同 updateVideo，文档缺失时兜底写入
            client.update(u -> u.index("user").id(user.getUid().toString()).doc(esUser).docAsUpsert(true), ESUser.class);
        } catch (IOException e) {
            log.error("更新ElasticSearch用户文档时出错了：" + e);
            throw e;
        }
    }

    /**
     * 查询相关用户数据数量
     * @param keyword
     * @return
     */
    public Long getUserCount(String keyword) {
        try {
            Query query = Query.of(q -> q.simpleQueryString(s -> s.fields("nickname").query(keyword).defaultOperator(Operator.And)));
            CountRequest countRequest = new CountRequest.Builder().index("user").query(query).build();
            CountResponse countResponse = client.count(countRequest);
            return countResponse.count();
        } catch (IOException e) {
            log.error("查询ES相关用户数量时出错了：" + e);
            return 0L;
        }
    }

    /**
     * 模糊匹配，分页查询
     * @param keyword   查询关键词
     * @param page  第几页 从1开始
     * @param size  每页查多少条数据 一般30条
     * @return 包含查到的数据id列表，按匹配分数排序
     */
    public List<Integer> searchUsersByKeyword(String keyword, Integer page, Integer size) {
        try {
            List<Integer> list = new ArrayList<>();
            Query query = Query.of(q -> q.simpleQueryString(s -> s.fields("nickname").query(keyword).defaultOperator(Operator.And)));
            SearchRequest searchRequest = new SearchRequest.Builder().index("user").query(query).from((page - 1) * size).size(size).build();
            SearchResponse<ESUser> searchResponse = client.search(searchRequest, ESUser.class);
            for (Hit<ESUser> hit : searchResponse.hits().hits()) {
                list.add(hit.source().getUid());
            }
            return list;
        } catch (IOException e) {
            log.error("查询ES相关用户文档时出错了：" + e);
            return Collections.emptyList();
        }
    }



    /**
     * 添加搜索词文档
     *
     * @param text
     */
    public void addSearchWord(String text) {
        try {
            ESSearchWord esSearchWord = new ESSearchWord(text);
            client.index(i -> i.index("search_word").document(esSearchWord));
        } catch (IOException e) {
            log.error("添加搜索词文档到elasticsearch时出错了：" + e);
        }
    }

    /**
     * 获取推荐搜索词
     * @param text
     * @return
     */
    public List<String> getMatchingWord(String text) {
        try {
            List<String> list = new ArrayList<>();
            Query query = Query.of(q -> q.simpleQueryString(s -> s.fields("content").query(text).defaultOperator(Operator.And)));   // 关键词全匹配
            Query query1 = Query.of(q -> q.prefix(p -> p.field("content").value(text)));
            Query bool = Query.of(q -> q.bool(b -> b.should(query).should(query1)));
            SearchRequest searchRequest = new SearchRequest.Builder().index("search_word").query(bool).from(0).size(10).build();
            SearchResponse<ESSearchWord> searchResponse = client.search(searchRequest, ESSearchWord.class);
            for (Hit<ESSearchWord> hit : searchResponse.hits().hits()) {
                list.add(hit.source().getContent());
            }
            return list;
        } catch (IOException e) {
            log.error("获取ES搜索提示词时出错了：" + e);
            return Collections.emptyList();
        }
    }
}

package com.teriteri.backend.service.impl.video;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.teriteri.backend.mapper.VideoMapper;
import com.teriteri.backend.mapper.VideoStatsMapper;
import com.teriteri.backend.pojo.CustomResponse;
import com.teriteri.backend.pojo.Video;
import com.teriteri.backend.pojo.VideoStats;
import com.teriteri.backend.pojo.dto.UserDTO;
import com.teriteri.backend.service.category.CategoryService;
import com.teriteri.backend.service.user.UserService;
import com.teriteri.backend.service.utils.CurrentUser;
import com.teriteri.backend.service.video.VideoService;
import com.teriteri.backend.service.video.VideoStatsService;
import com.teriteri.backend.utils.ESUtil;
import com.teriteri.backend.utils.OssUtil;
import com.teriteri.backend.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class VideoServiceImpl implements VideoService {
    @Autowired
    private VideoMapper videoMapper;

    @Autowired
    private VideoStatsMapper videoStatsMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private VideoStatsService videoStatsService;

    @Autowired
    private CurrentUser currentUser;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private OssUtil ossUtil;

    @Autowired
    private ESUtil esUtil;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;

    /**
     * 根据id分页获取视频信息，包括用户和分区信息
     * @param set   要查询的视频id集合
     * @param index 分页页码 为空默认是1
     * @param quantity  每一页查询的数量 为空默认是10
     * @return  包含用户信息、分区信息、视频信息的map列表
     */
    @Override
    public List<Map<String, Object>> getVideosWithDataByIds(Set<Object> set, Integer index, Integer quantity) {
        if (index == null) {
            index = 1;
        }
        if (quantity == null) {
            quantity = 10;
        }
        int startIndex = (index - 1) * quantity;
        int endIndex = startIndex + quantity;
        // 检查数据是否足够满足分页查询
        if (startIndex > set.size()) {
            // 如果数据不足以填充当前分页，返回空列表
            return Collections.emptyList();
        }
        List<Video> videoList = new CopyOnWriteArrayList<>();   // 使用线程安全的集合类 CopyOnWriteArrayList 保证多线程处理共享List不会出现并发问题

        // 直接数据库分页查询    （平均耗时 13ms）
        List<Object> idList = new ArrayList<>(set);
        endIndex = Math.min(endIndex, idList.size());
        List<Object> sublist = idList.subList(startIndex, endIndex);
        QueryWrapper<Video> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("vid", sublist).ne("status", 3);
        videoList = videoMapper.selectList(queryWrapper);
        if (videoList.isEmpty()) return Collections.emptyList();

        // 并行处理每一个视频，提高效率
        // 先将videoList转换为Stream
        Stream<Video> videoStream = videoList.stream();
        List<Map<String, Object>> mapList = videoStream.parallel() // 利用parallel()并行处理
                .map(video -> {
//                    long start = System.currentTimeMillis();
//                    System.out.println("================ 开始查询 " + video.getVid() + " 号视频相关信息 ===============   当前时间 " + start);
                    Map<String, Object> map = new HashMap<>();
                    map.put("video", video);

                    CompletableFuture<Void> userFuture = CompletableFuture.runAsync(() -> {
                        map.put("user", userService.getUserById(video.getUid()));
                        map.put("stats", videoStatsService.getVideoStatsById(video.getVid()));
                    }, taskExecutor);

                    CompletableFuture<Void> categoryFuture = CompletableFuture.runAsync(() -> {
                        map.put("category", categoryService.getCategoryById(video.getMcId(), video.getScId()));
                    }, taskExecutor);

                    // 使用join()等待全部任务完成
                    userFuture.join();
                    categoryFuture.join();
//                    long end = System.currentTimeMillis();
//                    System.out.println("================ 结束查询 " + video.getVid() + " 号视频相关信息 ===============   当前时间 " + end + "   耗时 " + (end - start));

                    return map;
                })
                .collect(Collectors.toList());

//        end = System.currentTimeMillis();
//        System.out.println("封装耗时：" + (end - start));
        return mapList;
    }

    @Override
    public List<Map<String, Object>> getVideosWithDataByIdsOrderByDesc(List<Integer> idList, @Nullable String column, Integer page, Integer quantity) {
        // 使用事务批量操作 减少连接sql的开销
        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            List<Map<String, Object>> result;
            if (column == null) {
                // 如果没有指定排序列，就按idList排序
                QueryWrapper<Video> queryWrapper = new QueryWrapper<>();
                queryWrapper.in("vid", idList);
                List<Video> videos = videoMapper.selectList(queryWrapper);
                if (videos.isEmpty()) {
                    sqlSession.commit();
                    return Collections.emptyList();
                }
                result = idList.stream().parallel().flatMap(vid -> {
                    Map<String, Object> map = new HashMap<>();
                    // 找到videos中为vid的视频
                    Video video = videos.stream()
                            .filter(v -> Objects.equals(v.getVid(), vid))
                            .findFirst()
                            .orElse(null);
                    if (video == null) return Stream.empty(); // 跳过该项
                    if (video.getStatus() == 3) {
                        // 视频已删除
                        Video video1 = new Video();
                        video1.setVid(video.getVid());
                        video1.setUid(video.getUid());
                        video1.setStatus(video.getStatus());
                        video1.setDeleteDate(video.getDeleteDate());
                        map.put("video", video1);
                        return Stream.of(map);
                    }
                    map.put("video", video);
                    CompletableFuture<Void> userFuture = CompletableFuture.runAsync(() -> {
                        map.put("user", userService.getUserById(video.getUid()));
                        map.put("stats", videoStatsService.getVideoStatsById(video.getVid()));
                    }, taskExecutor);
                    CompletableFuture<Void> categoryFuture = CompletableFuture.runAsync(() -> {
                        map.put("category", categoryService.getCategoryById(video.getMcId(), video.getScId()));
                    }, taskExecutor);
                    userFuture.join();
                    categoryFuture.join();
                    return Stream.of(map);
                }).collect(Collectors.toList());
            } else if (Objects.equals(column, "upload_date")) {
                // 如果按投稿日期排序，就先查video表
                QueryWrapper<Video> queryWrapper = new QueryWrapper<>();
                queryWrapper.in("vid", idList).orderByDesc(column).last("LIMIT " + quantity + " OFFSET " + (page - 1) * quantity);
                List<Video> list = videoMapper.selectList(queryWrapper);
                if (list.isEmpty()) {
                    sqlSession.commit();
                    return Collections.emptyList();
                }
                result = list.stream().parallel().map(video -> {
                    Map<String, Object> map = new HashMap<>();
                    if (video.getStatus() == 3) {
                        // 视频已删除
                        Video video1 = new Video();
                        video1.setVid(video.getVid());
                        video1.setUid(video.getUid());
                        video1.setStatus(video.getStatus());
                        video1.setDeleteDate(video.getDeleteDate());
                        map.put("video", video1);
                        return map;
                    }
                    map.put("video", video);
                    CompletableFuture<Void> userFuture = CompletableFuture.runAsync(() -> {
                        map.put("user", userService.getUserById(video.getUid()));
                        map.put("stats", videoStatsService.getVideoStatsById(video.getVid()));
                    }, taskExecutor);
                    CompletableFuture<Void> categoryFuture = CompletableFuture.runAsync(() -> {
                        map.put("category", categoryService.getCategoryById(video.getMcId(), video.getScId()));
                    }, taskExecutor);
                    userFuture.join();
                    categoryFuture.join();
                    return map;
                }).collect(Collectors.toList());
            } else {
                // 否则按视频数据排序，就先查数据
                QueryWrapper<VideoStats> queryWrapper = new QueryWrapper<>();
                queryWrapper.in("vid", idList).orderByDesc(column).last("LIMIT " + quantity + " OFFSET " + (page - 1) * quantity);
                List<VideoStats> list = videoStatsMapper.selectList(queryWrapper);
                if (list.isEmpty()) {
                    sqlSession.commit();
                    return Collections.emptyList();
                }
                result = list.stream().parallel().map(videoStats -> {
                    Map<String, Object> map = new HashMap<>();
                    Video video = videoMapper.selectById(videoStats.getVid());
                    if (video.getStatus() == 3) {
                        // 视频已删除
                        Video video1 = new Video();
                        video1.setVid(video.getVid());
                        video1.setUid(video.getUid());
                        video1.setStatus(video.getStatus());
                        video1.setDeleteDate(video.getDeleteDate());
                        map.put("video", video1);
                        return map;
                    }
                    map.put("video", video);
                    map.put("stats", videoStats);
                    CompletableFuture<Void> userFuture = CompletableFuture.runAsync(() -> {
                        map.put("user", userService.getUserById(video.getUid()));
                    }, taskExecutor);
                    CompletableFuture<Void> categoryFuture = CompletableFuture.runAsync(() -> {
                        map.put("category", categoryService.getCategoryById(video.getMcId(), video.getScId()));
                    }, taskExecutor);
                    userFuture.join();
                    categoryFuture.join();
                    return map;
                }).collect(Collectors.toList());
            }
            sqlSession.commit();
            return result;
        }
    }

    /**
     * 根据vid查询单个视频信息，包含用户信息和分区信息
     * @param vid 视频ID
     * @return 包含用户信息、分区信息、视频信息的map
     */
    @Override
    public Map<String, Object> getVideoWithDataById(Integer vid) {
        Map<String, Object> map = new HashMap<>();
        // 先查询 redis
        Video video = redisUtil.getObject("video:" + vid, Video.class);
        if (video == null) {
            // redis 查不到再查数据库
            QueryWrapper<Video> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("vid", vid).ne("status", 3);
            video = videoMapper.selectOne(queryWrapper);
            if (video != null) {
                Video finalVideo1 = video;
                CompletableFuture.runAsync(() -> {
                    redisUtil.setExObjectValue("video:" + vid, finalVideo1);    // 异步更新到redis
                }, taskExecutor);
            } else  {
                return null;
            }
        }

        // 多线程异步并行查询用户信息和分区信息并封装
        Video finalVideo = video;
        CompletableFuture<Void> userFuture = CompletableFuture.runAsync(() -> {
            map.put("user", userService.getUserById(finalVideo.getUid()));
            map.put("stats", videoStatsService.getVideoStatsById(finalVideo.getVid()));
        }, taskExecutor);
        CompletableFuture<Void> categoryFuture = CompletableFuture.runAsync(() -> {
            map.put("category", categoryService.getCategoryById(finalVideo.getMcId(), finalVideo.getScId()));
        }, taskExecutor);
        map.put("video", video);
        // 使用join()等待userFuture和categoryFuture任务完成
        userFuture.join();
        categoryFuture.join();

        return map;
    }

    /**
     * 根据有序vid列表查询视频以及相关信息
     * @param list  vid有序列表
     * @return  有序的视频列表
     */
    @Override
    public List<Map<String, Object>> getVideosWithDataByIdList(List<Integer> list) {
        if (list.isEmpty()) return Collections.emptyList();
        QueryWrapper<Video> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("vid", list).ne("status", 3);
        List<Video> videos = videoMapper.selectList(queryWrapper);
        if (videos.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> mapList = list.stream().parallel().flatMap(
                vid -> {
                    Map<String, Object> map = new HashMap<>();
                    // 找到videos中为vid的视频
                    Video video = videos.stream()
                            .filter(v -> Objects.equals(v.getVid(), vid))
                            .findFirst()
                            .orElse(null);

                    if (video == null) {
                        return Stream.empty(); // 跳过该项
                    }
                    map.put("video", video);

                    CompletableFuture<Void> userFuture = CompletableFuture.runAsync(() -> {
                        map.put("user", userService.getUserById(video.getUid()));
                        map.put("stats", videoStatsService.getVideoStatsById(video.getVid()));
                    }, taskExecutor);

                    CompletableFuture<Void> categoryFuture = CompletableFuture.runAsync(() -> {
                        map.put("category", categoryService.getCategoryById(video.getMcId(), video.getScId()));
                    }, taskExecutor);

                    userFuture.join();
                    categoryFuture.join();

                    return Stream.of(map);
                }
        ).collect(Collectors.toList());
        return mapList;
    }

    /**
     * 获取某个用户自己的全部稿件（创作中心-稿件管理），包含审核中、未通过等尚未公开的稿件
     * 与 getUserWorks 的区别：这里不能走"已过审"这类公开索引（未过审的稿件本来就不在里面），
     * 所以直接以数据库为准，并且只返回没被删除的
     * @param uid 用户ID
     * @return 稿件列表，每项含视频、用户、分区、统计数据
     */
    @Override
    public List<Map<String, Object>> getUserManuscripts(Integer uid) {
        QueryWrapper<Video> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("uid", uid).ne("status", 3).orderByDesc("upload_date").last("LIMIT 500");
        List<Video> videos = videoMapper.selectList(queryWrapper);
        if (videos.isEmpty()) return Collections.emptyList();
        UserDTO user = userService.getUserById(uid);    // 这批稿件都属于同一个人，查一次即可
        List<Map<String, Object>> result = new ArrayList<>(videos.size());
        for (Video video : videos) {
            Map<String, Object> map = new HashMap<>();
            map.put("video", video);
            map.put("user", user);
            map.put("stats", videoStatsService.getVideoStatsById(video.getVid()));
            map.put("category", categoryService.getCategoryById(video.getMcId(), video.getScId()));
            result.add(map);
        }
        return result;
    }

    /**
     * 更新视频状态，包括过审、不通过、删除，其中审核相关需要管理员权限，删除可以是管理员或者投稿用户
     * @param vid   视频ID
     * @param status 要修改的状态，1通过 2不通过 3删除
     * @return 无data返回，仅返回响应信息
     */
    // rollbackFor = Exception.class：本方法抛的是受检异常 IOException，而 Spring 默认只对
    // RuntimeException / Error 回滚；不显式声明的话，ES / Redis 更新失败时数据库的改动会被提交，
    // 出现"接口报错但数据库已改、名单和索引还是旧的"的状态分裂。声明后失败即全部撤销，可放心重试。
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomResponse updateVideoStatus(Integer vid, Integer status) throws IOException {
        CustomResponse customResponse = new CustomResponse();
        Integer userId = currentUser.getUserId();
        if (status == 1 || status == 2) {
            if (!currentUser.isAdmin()) {
                customResponse.setCode(403);
                customResponse.setMessage("您不是管理员，无权访问");
                return customResponse;
            }
            if (status == 1) {
                QueryWrapper<Video> queryWrapper = new QueryWrapper<>();
                queryWrapper.eq("vid", vid).ne("status", 3);
                Video video = videoMapper.selectOne(queryWrapper);
                if (video == null) {
                    customResponse.setCode(404);
                    customResponse.setMessage("视频不见了QAQ");
                    return customResponse;
                }
                Integer lastStatus = video.getStatus();
                video.setStatus(1);
                UpdateWrapper<Video> updateWrapper = new UpdateWrapper<>();
                updateWrapper.eq("vid", vid).set("status", 1).set("upload_date", new Date());     // 更新视频状态审核通过
                int flag = videoMapper.update(null, updateWrapper);
                if (flag > 0) {
                    // 更新成功
                    esUtil.updateVideo(video);  // 更新ES视频文档
                    redisUtil.delMember("video_status:" + lastStatus, vid);     // 从旧状态移除
                    redisUtil.addMember("video_status:1", vid);     // 加入新状态
                    redisUtil.delValue("video:" + vid);     // 删除旧的视频信息
                    return customResponse;
                } else {
                    // 更新失败，处理错误情况
                    customResponse.setCode(500);
                    customResponse.setMessage("更新状态失败");
                    return customResponse;
                }
            }
            else {
                // 目前逻辑跟上面一样的，但是可能以后要做一些如 记录不通过原因 等操作，所以就分开写了
                QueryWrapper<Video> queryWrapper = new QueryWrapper<>();
                queryWrapper.eq("vid", vid).ne("status", 3);
                Video video = videoMapper.selectOne(queryWrapper);
                if (video == null) {
                    customResponse.setCode(404);
                    customResponse.setMessage("视频不见了QAQ");
                    return customResponse;
                }
                Integer lastStatus = video.getStatus();
                video.setStatus(2);
                UpdateWrapper<Video> updateWrapper = new UpdateWrapper<>();
                updateWrapper.eq("vid", vid).set("status", 2);     // 更新视频状态审核不通过
                int flag = videoMapper.update(null, updateWrapper);
                if (flag > 0) {
                    // 更新成功
                    esUtil.updateVideo(video);  // 更新ES视频文档
                    redisUtil.delMember("video_status:" + lastStatus, vid);     // 从旧状态移除
                    redisUtil.addMember("video_status:2", vid);     // 加入新状态
                    redisUtil.delValue("video:" + vid);     // 删除旧的视频信息
                    return customResponse;
                } else {
                    // 更新失败，处理错误情况
                    customResponse.setCode(500);
                    customResponse.setMessage("更新状态失败");
                    return customResponse;
                }
            }
        } else if (status == 3) {
            QueryWrapper<Video> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("vid", vid).ne("status", 3);
            Video video = videoMapper.selectOne(queryWrapper);
            if (video == null) {
                customResponse.setCode(404);
                customResponse.setMessage("视频不见了QAQ");
                return customResponse;
            }
            if (Objects.equals(userId, video.getUid()) || currentUser.isAdmin()) {
                // OSS 上的对象 key（bucket 内的路径）。用 indexOf 而不是 split 取，
                // 这样 URL 不是 OSS 地址（比如历史遗留的本地文件路径）时只会拿到 null，
                // 而不是让 split 直接抛 ArrayIndexOutOfBoundsException 把整个删除卡死。
                String videoPrefix = ossKeyOf(video.getVideoUrl());     // OSS视频文件名
                String coverPrefix = ossKeyOf(video.getCoverUrl());     // OSS封面文件名
                Integer lastStatus = video.getStatus();
                UpdateWrapper<Video> updateWrapper = new UpdateWrapper<>();
                updateWrapper.eq("vid", vid).set("status", 3).set("delete_date", new Date());     // 更新视频状态已删除
                int flag = videoMapper.update(null, updateWrapper);
                if (flag > 0) {
                    // 更新成功
                    esUtil.deleteVideo(vid);
                    redisUtil.delMember("video_status:" + lastStatus, vid);     // 从旧状态移除
                    redisUtil.delValue("video:" + vid);     // 删除旧的视频信息
                    redisUtil.delValue("videoStats:" + vid);    // 删除统计数据缓存（缓存 miss 会回源数据库，安全）
                    redisUtil.delValue("danmu_idset:" + vid);   // 删除该视频的弹幕
                    // 批量删除该视频下的全部评论缓存
                    CompletableFuture.runAsync(() -> {
                        Set<Object> set = redisUtil.zReverange("comment_video:" + vid, 0, -1);
                        List<String> list = new ArrayList<>();
                        set.forEach(id -> list.add("comment_reply:" + id));
                        list.add("comment_video:" + vid);
                        redisUtil.delValues(list);
                    }, taskExecutor);
                    // OSS 源文件与封面等事务提交后再删（删除 OSS 不可逆，见方法内注释）
                    deleteOssFilesAfterCommit(videoPrefix, coverPrefix);
                    return customResponse;
                } else {
                    // 更新失败，处理错误情况
                    customResponse.setCode(500);
                    customResponse.setMessage("更新状态失败");
                    return customResponse;
                }
            } else {
                customResponse.setCode(403);
                customResponse.setMessage("您没有权限删除视频");
                return customResponse;
            }
        }
        customResponse.setCode(500);
        customResponse.setMessage("更新状态失败");
        return customResponse;
    }

    /**
     * 从 OSS 文件 URL 中取出 bucket 内的对象 key。
     * 形如 https://tlvk-teriteri.oss-cn-hangzhou.aliyuncs.com/2026-09-28/video/xxx.mp4 → 2026-09-28/video/xxx.mp4
     * @param url   文件的完整访问地址
     * @return  OSS 对象 key；不是 OSS 地址时返回 null
     */
    private static String ossKeyOf(String url) {
        if (url == null) return null;
        int index = url.indexOf("aliyuncs.com/");
        return index < 0 ? null : url.substring(index + "aliyuncs.com/".length());
    }

    /**
     * 把「删除 OSS 上的源文件与封面」推迟到当前事务提交之后再执行。
     * <p>
     * 原实现是在事务方法体内直接把删除任务丢给线程池，线程池可能立刻就开始执行。
     * 一旦这之后的步骤（例如 esUtil.deleteVideo）抛异常导致事务回滚，数据库里的稿件会被还原成"还在"，
     * 而源文件已经被真删掉了 —— 用户看着稿件还在、点进去却播不了，且无法恢复。
     * 删除 OSS 不可逆，必须等数据库那边确实提交成功了再动手。
     * <p>
     * afterCommit 里抛异常不会（也无法）回滚已经提交的事务，所以这里全部 try-catch，
     * 失败只把对象 key 打进日志，留待人工补偿。
     *
     * @param videoPrefix   OSS 上的视频源文件 key，为空则跳过
     * @param coverPrefix   OSS 上的封面文件 key，为空则跳过
     */
    private void deleteOssFilesAfterCommit(String videoPrefix, String coverPrefix) {
        Runnable deleteTask = () -> {
            if (videoPrefix != null) {
                try {
                    ossUtil.deleteFiles(videoPrefix);
                } catch (Exception e) {
                    log.error("删除 OSS 视频源文件失败，需人工清理。key={}", videoPrefix, e);
                }
            }
            if (coverPrefix != null) {
                try {
                    ossUtil.deleteFiles(coverPrefix);
                } catch (Exception e) {
                    log.error("删除 OSS 封面文件失败，需人工清理。key={}", coverPrefix, e);
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    CompletableFuture.runAsync(deleteTask, taskExecutor);
                }
            });
        } else {
            // 本方法带 @Transactional，正常走不到这里；万一事务没生效也不能让文件漏删
            CompletableFuture.runAsync(deleteTask, taskExecutor);
        }
    }
}

package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import jodd.util.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 2.查询blog有关的用户
        blog.setName(userService.getById(blog.getUserId()).getNickName());
        blog.setIcon(userService.getById(blog.getUserId()).getIcon());
        // 查询blog是否被点过赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        Long userId = UserHolder.getUser().getId();
        String key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        //判断当前用户是否已经点赞stringRedisTemplate
        Double score = stringRedisTemplate.opsForZSet().score("blog:liked:" + id, userId.toString());
        if (score == null) {
            // 3.如果未点赞，可以点赞
            boolean id1 = update().setSql("liked = liked + 1").eq("id", id).update();
            if (id1) {
                //数据库点赞数+1
                stringRedisTemplate.opsForZSet().add("blog:liked:" + id, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4.如果已点赞，取消点赞
            boolean id1 = update().setSql("liked = liked - 1").eq("id", id).update();
            if (id1) {
                //Redis UserID 取消
                stringRedisTemplate.opsForZSet().remove("blog:liked:" + id, userId.toString());
            }

        }
        return Result.ok();
    }

    /**
     * 实现点赞排行榜
     *
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikesById(Long id) {
        Set<String> top5 = stringRedisTemplate.opsForZSet().range("blog:liked:" + id, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //解析用户的id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(", ", ids);
        //查询用户信息
        List<UserDTO> users = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(users);
    }

    @Override
    public Result queryFollowBlog(Long max, Integer offset) {
        // 1.找到收件箱
        String key = FEED_KEY + UserHolder.getUser().getId();
        Set<ZSetOperations.TypedTuple<String>> typedTuples =
                stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 10);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            long time = typedTuple.getScore().longValue();
            if (minTime == time) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
            ids.add(Long.valueOf(typedTuple.getValue()));
        }
        List<Blog> followBogList = query().in("id", ids).last("ORDER BY FIELD(id," + StrUtil.join(",", ids) + ")").list();
        for (Blog blog : followBogList) {
            // 2.查询blog有关的用户
            User user = userService.getById(blog.getUserId());
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            // 查询blog是否被点过赞
            isBlogLiked(blog);
        }
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(followBogList);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }
}

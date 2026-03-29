package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result queryBlogById(Long id);

    Result likeBlog(Long id);

    /**
     * 查询当前用户点赞的博客
     * @param id
     * @return
     */
    Result queryBlogLikesById(Long id);

    /**
     * 查询当前用户关注人的最新blog
     * @param max
     * @param offset
     * @return
     */
    Result queryFollowBlog(Long max, Integer offset);
}

package com.hmdp.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Shop queryById(Long id);

    /**
     * 根据商铺类型查询商铺信息
     * @param typeId
     * @return
     */
    List<Shop> getShopListByType(Integer typeId);

    void updateShop(Shop shop);

    Page<Shop> queryShopPageByType(Integer typeId, Integer current);

    /**
     *  根据商铺类型分页查询商铺信息
     * @param typeId
     * @param current
     * @param x
     * @param y
     * @return
     */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}

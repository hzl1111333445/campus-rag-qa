package com.campus.rag.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化客户端：一段文字 → 一个 double 数组（向量）。
 *
 * <p><b>为什么先写"接口"而不是直接写一个类？</b>
 * 因为我们有两个实现：真的调模型的、和不用联网的假向量。
 * 上层代码（EmbeddingService）只认这个接口，不认具体是哪个实现。
 * 这样"换模型"只需要改一行配置，不用改任何业务代码。
 *
 * <p>如果直接把硅基流动的调用写死在业务代码里会怎样？
 * 将来想换阿里云、想在没网时跑测试、想对比两个模型的效果，
 * 都得去改业务代码——改一处漏一处，越改越乱。
 */
public interface EmbeddingClient {

    /** 模型名，会存进数据库，用来判断"这批向量是哪个模型算的" */
    String model();

    /** 向量维度，比如 1024。存库时也要存，维度不匹配要报错而不是硬算 */
    int dim();

    /**
     * 把一段文字变成向量。
     *
     * @param text 一段文字（通常是一个 chunk）
     * @return 长度为 dim() 的数组，已做归一化（模长 = 1）
     */
    double[] embed(String text);

    /**
     * 批量向量化。
     *
     * <p>为什么要有这个方法？因为 305 个块如果一条一条发请求，
     * 就是 305 次网络往返；批量发一次能省很多时间。
     *
     * <p>这里用 default 提供一个"默认实现"：不会批量也没关系，
     * 循环调用 embed() 就行。真正的实现类可以覆盖它，走一次请求发多条。
     *
     * <p><b>TODO（你来写）</b>：把下面这个"抛出异常"换成真正的循环实现。
     */
    default List<double[]> embedBatch(List<String> texts) {
        // 你的代码写在这里
        throw new UnsupportedOperationException("还没实现批量向量化");
    }
}

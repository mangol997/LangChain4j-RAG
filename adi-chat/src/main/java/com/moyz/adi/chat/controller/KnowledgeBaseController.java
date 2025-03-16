package com.moyz.adi.chat.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.moyz.adi.common.base.ThreadContext;
import com.moyz.adi.common.dto.KbEditReq;
import com.moyz.adi.common.dto.KbInfoResp;
import com.moyz.adi.common.dto.KbItemIndexBatchReq;
import com.moyz.adi.common.dto.KbSearchReq;
import com.moyz.adi.common.entity.AdiFile;
import com.moyz.adi.common.entity.KnowledgeBase;
import com.moyz.adi.common.service.KnowledgeBaseService;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/knowledge-base")
@Validated
public class KnowledgeBaseController {

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @PostMapping("/saveOrUpdate")
    public KnowledgeBase saveOrUpdate(@RequestBody KbEditReq kbEditReq) {
        return knowledgeBaseService.saveOrUpdate(kbEditReq);
    }

    @PostMapping(path = "/uploadDocs/{uuid}", headers = "content-type=multipart/form-data", produces = MediaType.APPLICATION_JSON_VALUE)
    public boolean uploadDocs(@PathVariable String uuid,
                              @RequestParam(value = "indexAfterUpload", defaultValue = "true") Boolean indexAfterUpload,
                              @RequestParam(defaultValue = "") String indexTypes,
                              @RequestParam("files") MultipartFile[] docs) {
        knowledgeBaseService.uploadDocs(uuid, indexAfterUpload, docs, List.of(indexTypes.split(",")));
        return true;
    }

    /**
     * 上传、解析并索引文档
     *
     * @param uuid             知识库uuid
     * @param indexAfterUpload 是否上传完接着索引文档
     * @param doc              二进制文件
     * @return 上传成功的文件信息
     */
    @PostMapping(path = "/upload/{uuid}", headers = "content-type=multipart/form-data", produces = MediaType.APPLICATION_JSON_VALUE)
    public AdiFile upload(@PathVariable String uuid,
                          @RequestParam(value = "indexAfterUpload", defaultValue = "true") Boolean indexAfterUpload,
                          @RequestParam(defaultValue = "") String indexTypes,
                          @RequestParam("file") MultipartFile doc) {
        return knowledgeBaseService.uploadDoc(uuid, indexAfterUpload, doc, List.of(indexTypes.split(",")));
    }

    /**
     * 搜索我的知识库
     *
     * @param keyword             搜索关键词
     * @param includeOthersPublic 是否包含其他人公开的知识库
     * @param currentPage         当前页数
     * @param pageSize            每页数量
     * @return 我的知识库列表
     */
    @GetMapping("/mine/search")
    public Page<KbInfoResp> searchMine(@RequestParam(defaultValue = "") String keyword,
                                       @RequestParam(defaultValue = "false") Boolean includeOthersPublic,
                                       @NotNull @Min(1) Integer currentPage,
                                       @NotNull @Min(10) Integer pageSize) {
        return knowledgeBaseService.searchMine(keyword, includeOthersPublic, currentPage, pageSize);
    }

    /**
     * 搜索公开的知识库
     *
     * @param keyword     搜索关键词
     * @param currentPage 当前页数
     * @param pageSize    每页数量
     * @return 知识库列表
     */
    @GetMapping("/public/search")
    public Page<KbInfoResp> searchPublic(@RequestParam(defaultValue = "") String keyword,
                                         @NotNull @Min(1) Integer currentPage,
                                         @NotNull @Min(10) Integer pageSize) {
        return knowledgeBaseService.search(KbSearchReq.builder().isPublic(true).title(keyword).build(), currentPage, pageSize);
    }

    /**
     * 知识库详情
     *
     * @param uuid 知识库uuid
     * @return 知识库详情
     */
    @GetMapping("/info/{uuid}")
    public KnowledgeBase info(@PathVariable String uuid) {
        return knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBase::getUuid, uuid)
                .eq(KnowledgeBase::getIsDeleted, false)
                .one();
    }

    /**
     * 删除知识库
     *
     * @param uuid 知识库uuid
     * @return 成功或失败
     */
    @PostMapping("/del/{uuid}")
    public boolean softDelete(@PathVariable String uuid) {
        return knowledgeBaseService.softDelete(uuid);
    }

    /**
     * 索引整个知识库
     *
     * @param uuid 知识库uuid
     * @return 成功或失败
     */
    @PostMapping("/indexing/{uuid}")
    public boolean indexing(@PathVariable String uuid, @RequestParam(defaultValue = "") String indexTypes) {
        return knowledgeBaseService.indexing(uuid, List.of(indexTypes.split(",")));
    }

    /**
     * 批量索引知识点
     *
     * @param req 知识点列表
     * @return 成功或失败
     */
    @PostMapping("/item/indexing-list")
    public boolean indexItems(@RequestBody KbItemIndexBatchReq req) {
        return knowledgeBaseService.indexItems(List.of(req.getUuids()), List.of(req.getIndexTypes()));
    }

    /**
     * 检查知识库是否已经索引完成
     *
     * @return 成功或失败
     */
    @GetMapping("/indexing/check")
    public boolean checkIndex() {
        return knowledgeBaseService.checkIndexIsFinish();
    }

    /**
     * 点赞
     *
     * @return true:star;false:unstar
     */
    @PostMapping("/star/toggle")
    public boolean star(@RequestParam @NotBlank String kbUuid) {
        return knowledgeBaseService.toggleStar(ThreadContext.getCurrentUser(), kbUuid);
    }
}

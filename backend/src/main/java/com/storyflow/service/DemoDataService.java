package com.storyflow.service;

import com.storyflow.dto.ProjectDtos;
import com.storyflow.entity.*;
import com.storyflow.repository.*;
import com.storyflow.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DemoDataService {

    public static final String DEMO_PROJECT_TITLE = "示例项目：雨中的约定";

    private final ProjectRepository projectRepository;
    private final SourceTextRepository sourceTextRepository;
    private final ScriptEntryRepository scriptEntryRepository;
    private final AssetRepository assetRepository;
    private final ShotRepository shotRepository;
    private final CurrentUserService currentUserService;
    private final AnonymousSessionService anonymousSessionService;
    private final JsonUtil jsonUtil;

    public DemoDataService(ProjectRepository projectRepository, SourceTextRepository sourceTextRepository,
                           ScriptEntryRepository scriptEntryRepository, AssetRepository assetRepository,
                           ShotRepository shotRepository, CurrentUserService currentUserService,
                           AnonymousSessionService anonymousSessionService, JsonUtil jsonUtil) {
        this.projectRepository = projectRepository;
        this.sourceTextRepository = sourceTextRepository;
        this.scriptEntryRepository = scriptEntryRepository;
        this.assetRepository = assetRepository;
        this.shotRepository = shotRepository;
        this.currentUserService = currentUserService;
        this.anonymousSessionService = anonymousSessionService;
        this.jsonUtil = jsonUtil;
    }

    public void seedIfAbsent() {
        if (!projectRepository.existsByTitle(DEMO_PROJECT_TITLE)) {
            createDemoProject();
        }
    }

    @Transactional
    public ProjectDtos.ProjectResponse createDemoProject() {
        Project project = new Project();
        project.setUserId(currentUserService.getCurrentUserId());
        if (currentUserService.isGuest()) {
            project.setAnonymousSessionId(anonymousSessionService.getCurrentSessionId());
        }
        project.setTitle(DEMO_PROJECT_TITLE);
        project.setStylePreset("日漫赛璐璐");
        projectRepository.save(project);
        Long projectId = project.getId();

        SourceText source = new SourceText();
        source.setProjectId(projectId);
        source.setContent("""
                初夏的午后，天空突然阴沉下来，细密的雨点砸在学校门口的柏油路上。

                白崎玲站在校门旁的屋檐下，手里握着一把还没撑开的折叠伞。她看了看手表，又抬头望向街道尽头。

                黑川朔从便利店的方向跑来，衬衫被雨淋湿了一角，手里拎着塑料袋。「你怎么不带伞？」玲把伞递过去一半，语气里带着嗔怪。

                「忘了看天气预报。」朔笑着接过伞柄，两人并肩站在屋檐下，雨声渐渐大了起来。

                玲轻轻撑开折叠伞，伞面上滑落的水珠在光线下折射出细碎的光芒。朔侧过头看她，玲别过脸，耳根微微泛红。

                远处传来上课铃响，两人对视一眼，同时笑了起来，一起撑伞跑进了雨里。
                """.trim());
        sourceTextRepository.save(source);

        ScriptEntry script1 = saveScript(projectId, 0, "日", "学校门口",
                List.of("白崎玲"),
                "白崎玲站在屋檐下等候，手里握着折叠伞，不时望向街道尽头。",
                "",
                "中景，固定机位，雨天氛围");

        ScriptEntry script2 = saveScript(projectId, 1, "日", "学校门口",
                List.of("白崎玲", "黑川朔"),
                "黑川朔从便利店方向跑来，衬衫被雨淋湿，白崎玲把伞递过去。",
                "白崎玲：「你怎么不带伞？」",
                "中景起，推至双人近景");

        ScriptEntry script3 = saveScript(projectId, 2, "日", "学校门口",
                List.of("白崎玲", "黑川朔"),
                "玲撑开折叠伞，两人并肩站在屋檐下，远处传来上课铃，一起撑伞跑进雨中。",
                "黑川朔：「走吧，要迟到了。」",
                "特写伞面水珠，拉至双人跑远");

        Asset charLing = saveAsset(projectId, AssetType.character, "白崎玲",
                "17岁女高中生，及肩黑发，穿校服，性格安静内敛",
                "角色设定参考图，16:9，左侧正脸头肩特写，右侧正面侧面背面全身等高横排，A字站姿，17岁女高中生，及肩黑发校服领结，特写与全身同一人同一服装，安静表情，纯白均匀光，日漫赛璐璐，干净线稿无文字");

        Asset charSaku = saveAsset(projectId, AssetType.character, "黑川朔",
                "17岁男高中生，短发，校服微湿，性格开朗",
                "角色设定参考图，16:9，左侧正脸头肩特写，右侧正面侧面背面全身等高横排，A字站姿，17岁男高中生，短发校服略湿，特写与全身同一人同一服装，开朗表情，纯白均匀光，日漫赛璐璐，干净线稿无文字");

        Asset sceneGate = saveAsset(projectId, AssetType.scene, "学校门口",
                "日式高中校门，屋檐遮阳棚，雨天柏油路，便利店招牌远景",
                "日漫环境设定主视觉，雨天高中校门，湿润柏油路与屋檐，远处便利店招牌，可带小 inset 另一角度，阴天光线，16:9");

        Asset propUmbrella = saveAsset(projectId, AssetType.prop, "折叠伞",
                "浅色折叠伞，伞面有水珠",
                "日漫道具多角度参考表，浅色折叠伞伞面水珠，正面侧面与四分之三视角同页，白底产品图，细节清晰");

        saveShot(projectId, script1.getId(), 0,
                "日漫赛璐璐，雨天学校门口中景，女孩站在屋檐下握着折叠伞等候，阴天光线，固定机位，约4秒",
                List.of(charLing.getId(), sceneGate.getId(), propUmbrella.getId()),
                List.of(
                        ref(ReferenceType.first_frame,
                                "日漫风格，雨天校门，女孩独自站在屋檐下握着收起的伞，中景，16:9",
                                "首帧：玲独自等雨"),
                        ref(ReferenceType.last_frame,
                                "日漫风格，女孩望向街道，雨丝落下，中景，16:9",
                                "尾帧：望向街道")
                ));

        saveShot(projectId, script2.getId(), 1,
                "日漫赛璐璐，中景推至近景，男孩在雨中跑向校门女孩，女孩递伞，镜头平滑推进，约5秒",
                List.of(charLing.getId(), charSaku.getId(), sceneGate.getId()),
                List.of(
                        ref(ReferenceType.first_frame,
                                "日漫风格，男孩在雨中跑向校门，中景，16:9",
                                "首帧：朔跑来"),
                        ref(ReferenceType.last_frame,
                                "日漫风格，男女站在屋檐下近处，女孩递伞，近景，16:9",
                                "尾帧：递伞近景"),
                        ref(ReferenceType.other,
                                "日漫特写，湿透的校服衬衫与雨珠细节",
                                "参考：湿透的衬衫")
                ));

        saveShot(projectId, script3.getId(), 2,
                "日漫赛璐璐，特写到全景，雨伞撑开水珠飞溅，两人共伞跑进雨中，远处上课铃，镜头后拉，约6秒",
                List.of(charLing.getId(), charSaku.getId(), sceneGate.getId(), propUmbrella.getId()),
                List.of(
                        ref(ReferenceType.first_frame,
                                "日漫特写，雨伞撑开，水珠反光，细节镜头，16:9",
                                "首帧：撑开雨伞"),
                        ref(ReferenceType.last_frame,
                                "日漫全景，两人共伞在雨中跑远，校门背景，16:9",
                                "尾帧：共撑伞跑远")
                ));

        return toResponse(project);
    }

    private ScriptEntry saveScript(Long projectId, int order, String time, String location,
                                   List<String> characters, String action, String dialogue, String shot) {
        ScriptEntry entry = new ScriptEntry();
        entry.setProjectId(projectId);
        entry.setTimeLabel(time);
        entry.setLocation(location);
        entry.setCharactersJson(jsonUtil.toJson(characters));
        entry.setAction(action);
        entry.setDialogue(dialogue);
        entry.setShot(shot);
        entry.setOrderIndex(order);
        return scriptEntryRepository.save(entry);
    }

    private Asset saveAsset(Long projectId, AssetType type, String name, String description, String imagePrompt) {
        Asset asset = new Asset();
        asset.setProjectId(projectId);
        asset.setType(type);
        asset.setName(name);
        asset.setDescription(description);
        asset.setImagePrompt(imagePrompt);
        return assetRepository.save(asset);
    }

    private void saveShot(Long projectId, Long scriptId, int order, String videoPrompt,
                          List<Long> linkedAssetIds, List<RefSeed> refs) {
        Shot shot = new Shot();
        shot.setProjectId(projectId);
        shot.setScriptId(scriptId);
        shot.setOrderIndex(order);
        shot.setVideoPrompt(videoPrompt);
        shot.setLinkedAssetIdsJson(jsonUtil.toJson(linkedAssetIds));

        int refOrder = 0;
        for (RefSeed seed : refs) {
            ReferencePrompt rp = new ReferencePrompt();
            rp.setShot(shot);
            rp.setType(seed.type);
            rp.setPrompt(seed.prompt);
            rp.setNote(seed.note);
            rp.setOrderIndex(refOrder++);
            shot.getReferencePrompts().add(rp);
        }
        shotRepository.save(shot);
    }

    private RefSeed ref(ReferenceType type, String prompt, String note) {
        return new RefSeed(type, prompt, note);
    }

    private ProjectDtos.ProjectResponse toResponse(Project project) {
        ProjectDtos.ProjectResponse resp = new ProjectDtos.ProjectResponse();
        resp.setId(project.getId());
        resp.setTitle(project.getTitle());
        resp.setStylePreset(project.getStylePreset());
        resp.setCreatedAt(project.getCreatedAt());
        resp.setUpdatedAt(project.getUpdatedAt());
        resp.setScriptCount(scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(project.getId()).size());
        resp.setShotCount(shotRepository.findByProjectIdWithRefs(project.getId()).size());
        return resp;
    }

    private record RefSeed(ReferenceType type, String prompt, String note) {}
}

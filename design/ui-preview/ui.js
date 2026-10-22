(function(){
'use strict';
const model=FilmLabModel.createModel(); window.filmLabPreview=model;
const diagnostics=FilmLabDiagnostics.create();
let state=model.getState(), textEditing=false, noticeTimer=null, transient='', flash=false;
let libraryNewFocused=false, renderedModal=false, modalReturnFocus=null, lastLcdFocus=null;
let editorControl='name', editorSaveIndex=0, detailActionIndex=0, settingsControl='flash', dialogActionIndex=0;
let supportPage='index', supportHistory=[], reportArtifact=null, reportStatus='idle';
let keyEvents=[], keySequence=0;
const flashDemo={mode:'front',compensationSteps:0};
const focusDemo={mode:'af-s',area:'wide'};
const focusOptions={mode:[['af-s','AF-S'],['af-c','AF-C'],['dmf','DMF'],['mf','MF']],area:[['wide','focusWide'],['zone','focusZone'],['center','focusCenter'],['flexible','focusFlexible']]};
const libraryTabs=[['favorites','favorites'],['builtin','builtin'],['user','myRecipes']];
const copy=value=>JSON.parse(JSON.stringify(value));
function flashState(){return {source:'browser-simulation',mode:flashDemo.mode,compensationEv:flashDemo.compensationSteps/3,animationActive:flash};}
function supportContext(){return {page:state.screen==='settings'?supportPage:state.screen,displayMode,flash:flashState()};}
function supportSnapshot(){return diagnostics.snapshot(state,supportContext());}
window.FilmLabSupport=Object.freeze({
 getPage:()=>state.screen==='settings'?supportPage:null,
 getFlash:()=>copy(flashState()),
 getFocus:()=>Object.assign({source:'browser-simulation'},focusDemo),
 getReportStatus:()=>({state:reportStatus,filename:reportArtifact?.filename||null,snapshotId:reportArtifact?.filename||null}),
 getEvents:()=>diagnostics.getEvents(),getSnapshot:()=>copy(supportSnapshot())
});
const DISPLAY_STORAGE_KEY='sony-pmca-film-lab:display-mode:v1';
const displayModes=['info','clean','histogram','level'];
let displayMode='info';
try{const stored=window.localStorage.getItem(DISPLAY_STORAGE_KEY);if(displayModes.includes(stored))displayMode=stored;}catch(error){}
window.FilmLabDisplay=Object.freeze({getMode:()=>displayMode});
const extra={en:{review:'INTERACTION STUDY / 01',title:'A familiar camera. Your own film.',subtitle:'Explore the menus, make a recipe, and see how it feels.',navigation:'Click the screen or use the camera controls.',keys:'↑ ↓ navigate · Enter select · Esc back',details:'Details',saveCurrent:'Save current look',unsaved:'Unsaved',sample:'ILLUSTRATIVE PREVIEW',physical:'The name field uses your computer keyboard. On-camera Chinese input still needs hardware validation.',footer:'Design proposal · 8 sample presets · 2 example user recipes · Changes stay in this browser.',reviewPrompt:'Review the menu layout, film icons and recipe flow. Share any changes in the conversation.',previewNotApplied:'Previewing — current shooting look is unchanged.',oldRevision:'Saved changes are not in use yet.',layout:'Sony-style menu',layoutText:'Compact rows, clear focus, physical-button navigation.',identity:'Your recipe',identityText:'Pencil marker · your name · editable settings.',reviewStep1:'Choose a look',reviewStep2:'Make it yours',reviewStep3:'Manage a recipe',reviewStep4:'Try both languages',view:'View',wbMode:'WB mode',activeDeletion:'The current look will remain in use as an unsaved look. You can save it again.',savedOnly:'Saved. The current shooting look is unchanged.',nameInput:'Name entry',nameInputNote:'English / 中文 · browser keyboard',mono:'MONO',color:'COLOR',copyright:'UI / UX PREVIEW',half:'Half-press',showShot:'Return to shooting'},zh:{review:'交互设计 / 01',title:'熟悉的相机，你的胶片风格。',subtitle:'浏览菜单，创建配方，体验完整操作流程。',navigation:'点击屏幕，或使用旁边的相机按键。',keys:'↑ ↓ 导航 · Enter 选择 · Esc 返回',details:'详情',saveCurrent:'保存当前效果',unsaved:'未保存',sample:'效果示意',physical:'名称输入使用电脑键盘。相机上的中文输入仍需实机验证。',footer:'设计提案 · 8 个示例预设 · 2 个示例用户配方 · 修改仅保存在此浏览器中。',reviewPrompt:'请体验菜单布局、胶片图标和配方流程，并在对话中告诉我需要调整的地方。',previewNotApplied:'正在预览，当前拍摄效果保持不变。',oldRevision:'新设置已保存，但尚未应用。',layout:'索尼风格菜单',layoutText:'紧凑列表、清晰焦点、实体按键操作。',identity:'你的专属配方',identityText:'铅笔标记 · 自定义名称 · 可编辑设置。',reviewStep1:'选择效果',reviewStep2:'创建专属配方',reviewStep3:'管理配方',reviewStep4:'切换中英文',view:'查看',wbMode:'白平衡模式',activeDeletion:'当前效果将继续使用，并标记为未保存。你可以重新保存该效果。',savedOnly:'已保存，当前拍摄效果保持不变。',nameInput:'名称输入',nameInputNote:'English / 中文 · 使用电脑键盘',mono:'黑白',color:'彩色',copyright:'界面与交互预览',half:'半按快门',showShot:'返回拍摄'}};
Object.keys(extra).forEach(lang=>Object.assign(FilmLabI18n[lang],extra[lang]));
Object.assign(FilmLabI18n.en,{up:'Up',down:'Down',left:'Left',right:'Right',newRecipeHint:'Choose a base film and make a new named recipe.'});
Object.assign(FilmLabI18n.zh,{up:'向上',down:'向下',left:'向左',right:'向右',newRecipeHint:'选择基础胶片，创建并命名你的新配方。'});
Object.assign(FilmLabI18n.en,{
 review:'INTERACTION STUDY / 02',reviewStatus:'Layout confirmed',
 navigation:'Camera keys work without touching the screen. Clicking is a review helper.',
 keys:'Arrows / Enter · Esc = MENU · F = Fn · D = DISP in shooting',
 physical:'This preview uses camera directions, center, Fn, MENU and DISP. Names use your computer keyboard; on-camera English/Chinese entry still needs hardware validation.',
 footer:'Layout and recipe flow confirmed · Physical-control study · Changes stay in this browser.',
 reviewPrompt:'Try the directional keys, editor adjustments and DISP views. The camera key mapping and enabled display modes still need hardware validation.',
 disp:'DISP',displayInfo:'Information',displayClean:'Clean display',displayHistogram:'Illustrative histogram · static',displayLevel:'Simulated level',
 fields:'Fields',adjust:'Adjust',tabs:'Tabs',actions:'Actions',options:'Options',nameOrSave:'Name / save',
 hintShoot:'DISP: display · Fn / center: recipes · MENU: settings',
 hintLibrary:'← → tabs · ↑ ↓ looks · center: details · MENU: back',
 hintEditor:'↑ ↓ fields · ← → adjust · center: name / save · MENU: back',
 hintDetail:'↑ ↓ actions · ← back · → action area · center: select',
 hintSettings:'↑ ↓ options · center: confirm · MENU: back',
 hintDialog:'← Cancel / Confirm → · center: select · MENU: cancel',
 nameTyping:'Computer keyboard: type name. Enter / Esc returns to the field.',
 nativeDirection:'Shooting left/right remain reserved for native camera functions.'
});
Object.assign(FilmLabI18n.zh,{
 review:'交互设计 / 02',reviewStatus:'布局已确认',
 navigation:'无需触摸屏幕即可使用相机按键；点击仅用于设计评审。',
 keys:'方向键 / Enter · Esc = MENU · F = Fn · 拍摄时 D = DISP',
 physical:'原型支持方向、中心、Fn、MENU 与 DISP。名称使用电脑键盘输入，相机上的中英文输入仍需实机验证。',
 footer:'布局与配方流程已确认 · 实体操控设计 · 修改仅保存在此浏览器中。',
 reviewPrompt:'请体验方向键、编辑参数与 DISP 显示。相机实际按键映射及启用的显示模式仍需实机验证。',
 disp:'DISP',displayInfo:'信息显示',displayClean:'简洁显示',displayHistogram:'直方图示意 · 静态',displayLevel:'水平仪示意',
 fields:'字段',adjust:'调整',tabs:'分类',actions:'操作',options:'选项',nameOrSave:'命名 / 保存',
 hintShoot:'DISP 切换显示 · Fn / 中心打开配方 · MENU 设置',
 hintLibrary:'← → 分类 · ↑ ↓ 配方 · 中心查看详情 · MENU 返回',
 hintEditor:'↑ ↓ 字段 · ← → 调整 · 中心命名 / 保存 · MENU 返回',
 hintDetail:'↑ ↓ 操作 · ← 返回 · → 操作区 · 中心选择',
 hintSettings:'↑ ↓ 选项 · 中心确认 · MENU 返回',
 hintDialog:'← 取消 / 确认 → · 中心选择 · MENU 取消',
 nameTyping:'使用电脑键盘输入名称，Enter / Esc 返回字段行。',
 nativeDirection:'拍摄时左右键保留给相机原生功能。'
});
Object.assign(FilmLabI18n.en,{
 review:'INTERACTION STUDY / 04',footer:'Compact preset browsing · Browser simulation · Physical controls.',
 cameraSettings:'Camera settings',focusMode:'Focus mode',focusArea:'Focus area',focusWide:'Wide',focusZone:'Zone',focusCenter:'Center',focusFlexible:'Flexible spot',
 focusDemoNote:'Illustrative choices only. The camera app opens Sony’s native focus menus; available options depend on the lens and shooting state.',
 noFavorites:'No favorites yet. Right: Built-in.',
 flashSettings:'Flash settings',flashMode:'Flash mode',flashCompensation:'Flash compensation',frontSync:'Front sync',slowSync:'Slow sync',rearSync:'Rear sync',
 flashDemoNote:'Illustrative controls only. This preview does not detect or control a flash. The camera app must use Sony’s original menu and supported values.',
 flashSeparate:'Example: −3…+3 EV in 1/3 EV steps. Flash choices are separate from recipes.',reportTitle:'Save issue report',aboutTitle:'About',developerTitle:'Developer tools',licensesTitle:'Licenses & sources',
 indexHint:'Choose a shooting setting, app preference or support page.',browserScope:'Browser prototype · camera not connected',
 reportNote:'Create a local browser TXT with app state and recent events. Recipe names, notes, photos and network identifiers are omitted.',
 reportNotCard:'This demonstration cannot write or verify a camera card. No report is uploaded.',downloadTxt:'Download TXT',downloadAgain:'Download TXT again',reportReady:'Ready to create a report snapshot.',
 reportRequested:'Download requested; check browser downloads.',reportFailed:'Could not request the download. The snapshot is retained; retry below.',
 reportFilename:'Filename',reportSnapshot:'Retained snapshot',reportNew:'New snapshot',reportNewHint:'Capture updated app state',reportEvents:'Recent app events',
 validationLabel:'Hardware validation',previewLabel:'UI preview',buildLabel:'Build',versionLabel:'Version',targetLabel:'Target',actualModel:'Actual camera',firmwareLabel:'Firmware',apiLabel:'Camera / Android API',
 unavailable:'Unavailable',notConnected:'Not connected',notValidated:'A7R II hardware is unvalidated',browserBuild:'Working-tree browser design',
 aboutNote:'Original MIT UI code and artwork. No upstream APK, camera code or LUT is embedded.',projectLabel:'Project / issues',sourcesAction:'View licenses & sources',
 licensesNote:'This browser prototype’s original UI code, SVG icons and demonstration artwork use the repository’s MIT license.',
 referencesLabel:'Design references',referencesNote:'Film Studio, OpenMemories and PMCA informed planning. Their code, APKs and LUT assets are not included in this preview.',
 futureLicenseNote:'A future camera build must keep each imported component’s actual license and notices.',
 deviceTitle:'Device & capabilities',processingTitle:'Processing status',keysTitle:'Key viewer',eventsTitle:'Recent app events',
 developerNote:'Read-only browser diagnostics. Hardware capability and readback values remain unavailable.',
 matrixLabel:'RGB matrix',gammaLabel:'Gamma curve',captureLabel:'Camera capture',nativeReadback:'Native readback',cameraCard:'Camera card',
 activeRecipe:'Active snapshot',savedRecipe:'Saved active recipe',highlightedRecipe:'Highlighted recipe',draftRecipe:'Draft',displayState:'Display illustration',
 noneLabel:'None',unsavedCurrent:'Unsaved current look',revisionLabel:'revision',browserState:'Browser state only',
 processingNote:'Shared diagnostic snapshot. It describes the UI, not an applied camera transform.',
 keysNote:'Browser key events only; Sony scan codes are unavailable. Inputs are observed here without shooting, Fn or DISP actions. MENU exits.',
 keyEmpty:'Press camera controls or keyboard keys to inspect events.',keyRedacted:'Printable key (redacted)',keyDown:'down',keyUp:'up',keyPress:'press',keyRepeat:'repeat',
 eventsNote:'Bounded app events, newest first. Reopen this page to refresh; use Up/Down to scroll.',eventsEmpty:'No recent app events.',
 hintSupport:'↑ ↓ rows · center: open · ← / MENU: back',hintFlash:'↑ ↓ settings · ← → adjust demo · MENU: back',hintReadout:'↑ ↓ scroll · ← / MENU: back',
 hintKeys:'Keys are observed only · MENU / Esc: exit',hintReport:'↑ ↓ actions · center: download · ← / MENU: back'
});
Object.assign(FilmLabI18n.zh,{
 review:'交互设计 / 04',footer:'紧凑预设浏览 · 浏览器模拟 · 实体按键操作。',
 cameraSettings:'相机设置',focusMode:'对焦模式',focusArea:'对焦区域',focusWide:'广域',focusZone:'区',focusCenter:'中间',focusFlexible:'自由点',
 focusDemoNote:'仅为选项示意。相机应用打开索尼原生对焦菜单，可用选项由镜头和拍摄状态决定。',
 noFavorites:'暂无收藏。右键：内置。',
 flashSettings:'闪光灯设置',flashMode:'闪光模式',flashCompensation:'闪光补偿',frontSync:'前帘同步',slowSync:'慢速同步',rearSync:'后帘同步',
 flashDemoNote:'仅为控件示意，未检测或控制闪光灯。相机应用须沿用索尼原生菜单及实际支持值。',
 flashSeparate:'示例范围：−3…+3 EV，步进 1/3 EV。闪光设置独立于胶片配方。',reportTitle:'保存问题报告',aboutTitle:'关于',developerTitle:'开发者工具',licensesTitle:'许可与来源',
 indexHint:'选择拍摄设置、应用偏好或支持页面。',browserScope:'浏览器原型 · 未连接相机',
 reportNote:'生成包含应用状态与最近事件的本地浏览器 TXT。默认省略配方名称、备注、照片和网络标识。',
 reportNotCard:'此演示不能写入或验证相机存储卡，也不会上传报告。',downloadTxt:'下载 TXT',downloadAgain:'再次下载 TXT',reportReady:'可生成一份报告快照。',
 reportRequested:'已请求下载，请检查浏览器下载列表。',reportFailed:'无法请求下载。快照已保留，请在下方重试。',
 reportFilename:'文件名',reportSnapshot:'保留的快照',reportNew:'更新快照',reportNewHint:'采集最新的应用状态',reportEvents:'最近应用事件',
 validationLabel:'实机验证',previewLabel:'界面预览',buildLabel:'构建',versionLabel:'版本',targetLabel:'目标机型',actualModel:'实际相机',firmwareLabel:'固件',apiLabel:'相机 / Android API',
 unavailable:'不可用',notConnected:'未连接',notValidated:'尚未验证 A7R II 实机',browserBuild:'工作区浏览器设计版本',
 aboutNote:'原创界面代码与美术使用 MIT 许可，未嵌入上游 APK、相机代码或 LUT。',projectLabel:'项目 / 问题反馈',sourcesAction:'查看许可与来源',
 licensesNote:'此浏览器原型的原创界面代码、SVG 图标和示意美术采用仓库的 MIT 许可。',
 referencesLabel:'设计参考',referencesNote:'方案参考了 Film Studio、OpenMemories 与 PMCA；此原型未包含其代码、APK 或 LUT 素材。',
 futureLicenseNote:'未来相机版本须保留各导入组件实际适用的许可与通知。',
 deviceTitle:'设备与能力',processingTitle:'处理状态',keysTitle:'按键查看',eventsTitle:'最近应用事件',
 developerNote:'只读浏览器诊断；硬件能力与相机读回值仍不可用。',
 matrixLabel:'RGB 矩阵',gammaLabel:'Gamma 曲线',captureLabel:'相机拍摄',nativeReadback:'原生读回',cameraCard:'相机存储卡',
 activeRecipe:'当前效果快照',savedRecipe:'对应的已保存配方',highlightedRecipe:'浏览选中配方',draftRecipe:'草稿',displayState:'显示示意',
 noneLabel:'无',unsavedCurrent:'当前效果未保存',revisionLabel:'版本',browserState:'仅为浏览器状态',
 processingNote:'使用同一诊断快照描述界面状态，并非已应用到相机的处理参数。',
 keysNote:'仅观察浏览器按键，无法读取索尼扫描码。本页不执行拍摄、Fn 或 DISP 操作；MENU 返回。',
 keyEmpty:'按相机控件或电脑键盘可查看事件。',keyRedacted:'可打印按键（已隐去）',keyDown:'按下',keyUp:'松开',keyPress:'点击',keyRepeat:'重复',
 eventsNote:'有界的应用事件按最新在前显示。重新进入可刷新，上下键滚动。',eventsEmpty:'暂无最近应用事件。',
 hintSupport:'↑ ↓ 项目 · 中心打开 · ← / MENU 返回',hintFlash:'↑ ↓ 设置 · ← → 调整示意值 · MENU 返回',hintReadout:'↑ ↓ 滚动 · ← / MENU 返回',
 hintKeys:'仅观察按键 · MENU / Esc 返回',hintReport:'↑ ↓ 操作 · 中心下载 · ← / MENU 返回'
});
Object.assign(FilmLabI18n.en,{languageNote:'Choose interface language. Recipe names stay as entered.',languageApplyHint:'Up/Down chooses an option. Center applies it.',scroll:'Scroll',open:'Open',apply:'Apply',exit:'Exit',observe:'Observe',otherKeys:'Other keys',sourcesShort:'Sources'});
Object.assign(FilmLabI18n.zh,{languageNote:'选择界面语言，配方名称保持原样。',languageApplyHint:'上下键选择，中心键应用。',scroll:'滚动',open:'打开',apply:'应用',exit:'退出',observe:'观察',otherKeys:'其他键',sourcesShort:'来源'});
FilmLabI18n.en.deleteActiveBody=extra.en.activeDeletion;FilmLabI18n.zh.deleteActiveBody=extra.zh.activeDeletion;
const t=key=>FilmLabI18n[state.lang][key]||key;
const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const name=r=>typeof r.name==='string'?r.name:r.name[state.lang];
const record=id=>state.records.find(r=>r.id===id);
const isActive=r=>r&&r.id===state.active.id&&r.revision===state.active.revision;
function icon(kind,size=20){const p={arrow:'<path d="m14 5-7 7 7 7"/>',right:'<path d="m9 5 7 7-7 7"/>',star:'<path d="m12 3 2.8 5.8 6.4.9-4.6 4.5 1.1 6.3-5.7-3-5.7 3 1.1-6.3-4.6-4.5 6.4-.9Z"/>',plus:'<path d="M12 4v16M4 12h16"/>',edit:'<path d="m4 16 12-12 4 4-12 12-5 1Z M13 7l4 4"/>',copy:'<path d="M8 8h12v12H8zM4 16V4h12"/>',trash:'<path d="M4 6h16M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7M14 10v7"/>',check:'<path d="m5 12 4 4L20 5"/>',lock:'<path d="M6 10h12v11H6zM8 10V6a4 4 0 0 1 8 0v4"/>',settings:'<circle cx="12" cy="12" r="4"/><path d="M12 2v3m0 14v3M2 12h3m14 0h3M5 5l2 2m10 10 2 2M5 19l2-2M17 7l2-2"/>',camera:'<path d="M3 7h5l2-3h5l2 3h4v14H3Z"/><circle cx="12" cy="14" r="4"/>',rename:'<path d="m3 19 5-14 5 14M5 14h6M16 5h5M18.5 5v14M16 19h5"/>',battery:'<path d="M3 6h17v12H3zM22 10v4M6 9v6M9 9v6M12 9v6M15 9v6"/>'};return `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="square" stroke-linejoin="miter" aria-hidden="true">${p[kind]||p.camera}</svg>`;}
function film(r={},size=48){return `<svg width="${size}" height="${size*.86}" viewBox="0 0 70 60" fill="none" aria-hidden="true"><g stroke="currentColor" stroke-width="2.4"><path d="M9 10h29v43H9zM6 7h35v6H6zM6 50h35v6H6zM18 3h12v4M41 19h23v22H41"/><path d="M47 21v4m8-4v4m6-4v4M47 35v4m8-4v4m6-4v4" stroke-width="3"/></g>${r.code?`<text x="23" y="35" font-size="15" font-family="Arial,sans-serif" text-anchor="middle" fill="currentColor" font-weight="bold">${esc(r.code)}</text><path d="M14 43h19" stroke="${esc(r.accent||'#aaa')}" stroke-width="3"/>`:''}${r.origin==='user'?'<path d="m45 48 12-12 6 6-12 12-8 2Z" fill="#1b2115" stroke="currentColor" stroke-width="2.4"/>':''}</svg>`;}
function btn(action,label,iconName='',payload={},cls=''){return `<button class="btn ${cls}" data-action="${action}" data-payload='${esc(JSON.stringify(payload))}'>${iconName?icon(iconName,17):''}${esc(t(label))}</button>`;}
function currentPreview(){return state.screen==='editor'?state.draft:state.screen==='library'&&libraryNewFocused?state.active:(state.screen==='library'||state.screen==='detail')?record(state.selectedId)||state.active:state.active;}
// CSS is only an illustrative response to editor controls, not a camera transform.
function colorFilter(r){if(!r)return '';const s=r.settings,b=r.baseId;const sat=r.mono?0:(.82+(s.color||0)/180);const contrast=1+(s.tone||0)/1200-(s.shadows||0)*.025+(s.highlights||0)*.015;const warmth=(s.wb==='kelvin'?(s.kelvin-5500)/4500:0)+(s.ab||0)/14;const sep=Math.max(0,(r.mono?0:b==='negative'?.20:b==='classic-chrome'?.10:0)+warmth*.12);return `saturate(${sat}) contrast(${contrast}) brightness(${1+(s.shadows||0)*.013+(s.highlights||0)*.01}) sepia(${sep}) hue-rotate(${-warmth*12+(s.gm||0)*2}deg)${r.mono?' grayscale(1)':''}`;}
function header(title,kind='camera'){return `<div class="screen-head"><div class="head-title">${kind==='film'?film({},30):icon(kind,24)}${esc(t(title))}</div><button class="back-button" data-action="back">${icon('arrow',16)}${esc(t('back'))}</button></div>`;}
function footer(){
 let hints;
 if(state.screen==='settings'&&!state.modal){
  if(supportPage==='keys')hints=[['MENU','exit'],[t('otherKeys'),'observe']];
  else if(supportPage==='flash')hints=[['MENU','back'],['↑ ↓','fields'],['← →','adjust']];
  else if(['device','processing','licenses','events'].includes(supportPage))hints=[['MENU','back'],['↑ ↓','scroll']];
  else if(supportPage==='about')hints=[['MENU','back'],['↑ ↓','scroll'],['●','sourcesShort']];
  else hints=[['MENU','back'],['↑ ↓',supportPage==='report'?'actions':'options'],['●',['language','focus-mode','focus-area'].includes(supportPage)?'apply':'open']];
 }else{
  const context=state.modal?'dialog':state.screen;
  const middle=context==='editor'?['↑ ↓','fields']:context==='library'?['↑ ↓','view']:context==='detail'?['↑ ↓','actions']:['↑ ↓','options'];
  const lateral=context==='editor'?['← →','adjust']:context==='library'?['← →','tabs']:context==='detail'?['←','back']:context==='dialog'?['← →','actions']:null;
  hints=[['MENU','back'],middle,...(lateral?[lateral]:[]),['●',context==='editor'?'nameOrSave':'select']];
 }
 return `<div class="screen-footer">${hints.map(([key,label])=>`<span><kbd>${esc(key)}</kbd>${esc(t(label))}</span>`).join('')}</div>`;
}
function scene(){return `<div class="scene" style="filter:${colorFilter(currentPreview())}"></div>`;}
function shooting(){
 if(displayMode==='clean')return scene();
 const extraDisplay=displayMode==='histogram'?`<div class="histogram" role="img" aria-label="${esc(t('displayHistogram'))}"><span class="display-label">${esc(t('displayHistogram'))}</span><svg width="170" height="78" viewBox="0 0 170 78" aria-hidden="true"><path d="M5 70H165M5 5V70" fill="none" stroke="currentColor"/><path d="M8 69V63H16V54H24V57H32V42H40V34H48V39H56V25H64V16H72V21H80V11H88V24H96V32H104V29H112V44H120V48H128V41H136V53H144V59H152V65H160V69Z" fill="currentColor" opacity=".7"/></svg></div>`:displayMode==='level'?`<div class="level-gauge" role="img" aria-label="${esc(t('displayLevel'))}"><span class="display-label">${esc(t('displayLevel'))}</span><svg width="190" height="90" viewBox="0 0 190 90" aria-hidden="true"><path d="M10 45H75M115 45H180M95 12V30M95 60V78" stroke="currentColor" stroke-width="2"/><path d="M60 45H130" stroke="#a2d88f" stroke-width="3"/><circle cx="95" cy="45" r="11" fill="none" stroke="#a2d88f" stroke-width="2"/></svg></div>`:'';
 return `${scene()}<div class="hud-top"><span><b class="mode">A</b> &nbsp; AF-S &nbsp; RAW+J</span><span>3:2 &nbsp; ${icon('battery',25)}</span></div><div class="shoot-menu"><button class="icon-btn" data-action="openLibrary">${film({},24)}${esc(t('filmPresets'))}</button><button class="icon-btn" data-action="settings" aria-label="${esc(t('settings'))}">${icon('settings',20)}</button></div><div class="focus-bracket"></div><button class="shoot-recipe" data-action="openLibrary">${film({...state.active,origin:record(state.active.id)?.origin},44)}<span><small>${esc(t('active'))}</small><b>${esc(state.active.name)}</b></span>${icon('right',16)}</button>${state.active.id===null?`<div class="shoot-save">${btn('saveCurrent','saveCurrent','plus')}</div>`:''}${extraDisplay}<div class="hud-bottom"><span>1/250</span><span>F2.8</span><span>±0.0</span><span><small>ISO</small> 200</span></div>`;
}
function visible(){return state.records.filter(r=>state.tab==='user'?r.origin==='user':state.tab==='favorites'?state.favorites.includes(r.id):r.origin==='builtin');}
function library(){
 const rows=visible(),newFocused=state.tab==='user'&&libraryNewFocused;
 return `${scene()}${header('filmPresets','film')}<div class="library-tabs">${libraryTabs.map(([v,key])=>`<button data-action="tab" data-payload='{"tab":"${v}"}' class="${state.tab===v?'selected':''}">${v==='favorites'?icon('star',16):''}${esc(t(key))}</button>`).join('')}</div><div class="library-layout"><div class="recipe-list" data-scroll="list">${rows.length?rows.map(x=>`<button class="recipe-row ${x.id===state.selectedId&&!newFocused?'selected':''}" data-action="select" data-id="${x.id}" data-payload='{"id":"${x.id}"}' aria-pressed="${x.id===state.selectedId&&!newFocused}">${film(x,32)}<span class="recipe-label"><b>${esc(name(x))}</b></span>${isActive(x)?'<span class="check">✓</span>':''}</button>`).join(''):`<div class="empty">${esc(t(state.tab==='favorites'?'noFavorites':'noRecipes'))}</div>`}${state.tab==='user'?`<button class="new-row ${newFocused?'selected':''}" data-action="create" aria-pressed="${newFocused}">${icon('plus',18)}${esc(t('newRecipe'))}</button>`:''}</div></div>${footer()}`;
}
function summary(r){return `<div class="settings-summary">${[['color',r.mono?'—':r.settings.color+'%'],['tone',r.settings.tone+'%'],['shadows',signed(r.settings.shadows)],['highlights',signed(r.settings.highlights)],['wb',r.settings.wb==='auto'?t('auto'):r.settings.kelvin+'K'],['ab',axis('ab',r.settings.ab)],['gm',axis('gm',r.settings.gm)]].map(([k,v])=>`<div class="summary-item"><span>${esc(t(k))}</span><b>${esc(v)}</b></div>`).join('')}</div>`;}
function detail(){const r=record(state.selectedId);if(!r)return library();return `${scene()}${header('recipeDetails','film')}<div class="detail-content"><div class="detail-main"><div class="detail-identity">${film(r,65)}<div><h2>${esc(name(r))}</h2><small>${esc(t(r.origin==='builtin'?'builtin':'user'))}${isActive(r)?' · '+esc(t('active')):''}</small></div></div><p class="detail-description">${esc(r.description[state.lang])}</p>${summary(r)}${r.id===state.active.id&&!isActive(r)?`<p class="readonly-note">${esc(t('oldRevision'))}</p>`:''}</div><div class="detail-actions">${btn('use','use','check',{id:r.id},'primary')}${btn('favorite',state.favorites.includes(r.id)?'unfavorite':'favorite','star',{id:r.id})}${r.origin==='user'?btn('edit','edit','edit',{id:r.id}):''}${btn('duplicate',r.origin==='builtin'?'duplicateCustomize':'duplicate','copy',{id:r.id})}${r.origin==='user'?btn('rename','rename','rename',{id:r.id})+btn('delete','delete','trash',{id:r.id}):`<p class="readonly-note">${icon('lock',15)}${esc(t('builtinHint'))}</p>`}</div></div>${footer()}`;}
const signed=n=>n>0?'+'+n:String(n);
function axis(key,n){return n===0?'0':(key==='ab'?(n>0?'A':'B'):(n>0?'G':'M'))+' '+Math.abs(n);}
function fieldAttrs(key){return `data-control="${key}" tabindex="-1"`;}
function stepper(key,min,max,step=1,suffix=''){
 const v=state.draft.settings[key];
 return `<div class="field ${editorControl===key?'focused':''}" ${fieldAttrs(key)} data-min="${min}" data-max="${max}" data-step-size="${step}"><label>${esc(t(key))}</label><div class="stepper"><button data-step="${key}" data-change="${-step}" aria-label="${esc(t(key))} −" ${v<=min?'disabled':''}>−</button><output>${esc(key==='ab'||key==='gm'?axis(key,v):signed(v).replace(/^\+/,key==='shadows'||key==='highlights'?'+':'')+suffix)}</output><button data-step="${key}" data-change="${step}" aria-label="${esc(t(key))} +" ${v>=max?'disabled':''}>+</button></div></div>`;
}
function editor(){
 const r=state.draft;if(!r)return library();
 return `${scene()}${header('editing','edit')}<div class="editor-content"><div class="editor-fields" data-scroll="fields"><div class="field ${editorControl==='name'?'focused':''}" ${fieldAttrs('name')}><label for="recipe-name">${esc(t('recipeName'))}</label><input id="recipe-name" data-field="name" type="text" value="${esc(r.name)}" autocomplete="off" spellcheck="false" aria-describedby="name-help"></div><p class="field-note" id="name-help">${esc(t('nameHint'))} ${esc(t('nameTyping'))}</p><div class="field ${editorControl==='baseId'?'focused':''}" ${fieldAttrs('baseId')}><label for="base-film">${esc(t('baseFilm'))}</label><select id="base-film" data-field="baseId">${state.records.filter(x=>x.origin==='builtin').map(x=>`<option value="${x.id}" ${r.baseId===x.id?'selected':''}>${esc(name(x))}</option>`).join('')}</select></div>${!r.mono?stepper('color',0,100,5,'%'):`<p class="field-note">${esc(t('colorHidden'))}</p>`}${stepper('tone',0,100,5,'%')}${stepper('shadows',-3,3)}${stepper('highlights',-3,3)}<div class="field ${editorControl==='wb'?'focused':''}" ${fieldAttrs('wb')}><label for="wb-mode">${esc(t('wb'))}</label><select id="wb-mode" data-field="wb"><option value="auto" ${r.settings.wb==='auto'?'selected':''}>${esc(t('auto'))}</option><option value="kelvin" ${r.settings.wb==='kelvin'?'selected':''}>${esc(t('kelvin'))} (K)</option></select></div>${r.settings.wb==='kelvin'?stepper('kelvin',2500,10000,100,'K'):''}${stepper('ab',-7,7)}${stepper('gm',-7,7)}</div><div class="editor-preview"><span class="preview-tag">${esc(t('unsaved'))}</span>${film(r,52)}<p>${esc(t('previewNotApplied'))}</p></div></div><div class="editor-bottom" data-control="save-actions" tabindex="-1"><small>${esc(t('savedOnlyHint'))}</small><div style="display:flex;gap:8px">${btn('save','save','',{use:false},editorControl==='save-actions'&&editorSaveIndex===0?'focused':'')}${btn('save','saveUse','check',{use:true},'primary'+(editorControl==='save-actions'&&editorSaveIndex===1?' focused':''))}</div></div>${footer()}`;
}
function supportRow(id,label,hint='',page=id){
 return `<button class="support-row ${settingsControl===id?'focused':''}" data-setting="${id}" data-support="open" data-page="${page}"><span><b>${esc(t(label))}</b>${hint?`<small>${esc(t(hint))}</small>`:''}</span>${icon('right',16)}</button>`;
}
function supportFrame(title,body,kind='settings'){return `${scene()}${header(title,kind)}<div class="support-page" data-support-content>${body}</div>${footer()}`;}
function statusRows(rows){return `<dl class="support-status">${rows.map(([label,value])=>`<div><dt>${esc(t(label))}</dt><dd>${esc(value)}</dd></div>`).join('')}</dl>`;}
function recipeSummary(recipe){return !recipe?t('noneLabel'):`${recipe.id||t('unsavedCurrent')} · ${t('revisionLabel')} ${recipe.revision||'—'}`;}
function flashEv(){const steps=flashDemo.compensationSteps;if(!steps)return '0 EV';const value=Math.abs(steps),whole=Math.floor(value/3),fraction=value%3;return `${steps<0?'−':'+'}${whole||!fraction?whole:''}${whole&&fraction?' ':''}${fraction?fraction+'/3':''} EV`;}
function settings(){
 const snap=supportSnapshot();
 if(supportPage==='index')return supportFrame('settings',`<p class="support-note">${esc(t('indexHint'))}</p><div class="support-list" data-scroll="support">${supportRow('flash','flashSettings')}${supportRow('camera','cameraSettings')}${supportRow('language','language')}${supportRow('report','reportTitle')}${supportRow('about','aboutTitle')}${supportRow('developer','developerTitle')}${state.active.id===null?`<button class="support-row ${settingsControl==='saveCurrent'?'focused':''}" data-setting="saveCurrent" data-action="saveCurrent"><span><b>${esc(t('saveCurrent'))}</b></span>${icon('plus',18)}</button>`:''}</div>`);
 if(supportPage==='camera')return supportFrame('cameraSettings',`<p class="support-note demo-scope">${esc(t('focusDemoNote'))}</p><div class="support-list">${supportRow('focus-mode','focusMode',focusOptions.mode.find(([value])=>value===focusDemo.mode)[1])}${supportRow('focus-area','focusArea',focusOptions.area.find(([value])=>value===focusDemo.area)[1])}</div>`);
 if(supportPage==='focus-mode'||supportPage==='focus-area'){
  const kind=supportPage==='focus-mode'?'mode':'area';
  return supportFrame(kind==='mode'?'focusMode':'focusArea',`<p class="support-note demo-scope">${esc(t('focusDemoNote'))}</p><div class="support-list">${focusOptions[kind].map(([value,label])=>`<button class="support-row ${focusDemo[kind]===value?'selected':''} ${settingsControl===value?'focused':''}" data-setting="${value}" data-support="focus-select" data-focus="${kind}" data-value="${value}"><span><b>${esc(t(label))}</b></span><span>${focusDemo[kind]===value?'✓':''}</span></button>`).join('')}</div>`);
 }
 if(supportPage==='language')return supportFrame('language',`<p class="support-note">${esc(t('languageNote'))}</p><div class="support-list">${[['en','English'],['zh','简体中文']].map(([lang,label])=>`<button class="support-row ${state.lang===lang?'selected':''} ${settingsControl===lang?'focused':''}" data-setting="${lang}" data-action="language" data-payload='{"lang":"${lang}"}'><span><b>${label}</b></span><span>${state.lang===lang?'✓':''}</span></button>`).join('')}</div><p class="support-note">${esc(t('languageApplyHint'))}</p>`);
 if(supportPage==='flash')return supportFrame('flashSettings',`<p class="support-note demo-scope">${esc(t('flashDemoNote'))}</p><div class="support-flash-row ${settingsControl==='flash-mode'?'focused':''}" data-setting="flash-mode" tabindex="-1"><label>${esc(t('flashMode'))}</label><div class="support-stepper"><button data-support="flash-adjust" data-flash="mode" data-change="-1" aria-label="${esc(t('left'))}">−</button><output>${esc(t({front:'frontSync',slow:'slowSync',rear:'rearSync'}[flashDemo.mode]))}</output><button data-support="flash-adjust" data-flash="mode" data-change="1" aria-label="${esc(t('right'))}">+</button></div></div><div class="support-flash-row ${settingsControl==='flash-compensation'?'focused':''}" data-setting="flash-compensation" tabindex="-1"><label>${esc(t('flashCompensation'))}</label><div class="support-stepper"><button data-support="flash-adjust" data-flash="compensation" data-change="-1" aria-label="${esc(t('flashCompensation'))} −" ${flashDemo.compensationSteps<=-9?'disabled':''}>−</button><output>${esc(flashEv())}</output><button data-support="flash-adjust" data-flash="compensation" data-change="1" aria-label="${esc(t('flashCompensation'))} +" ${flashDemo.compensationSteps>=9?'disabled':''}>+</button></div></div><p class="support-note">${esc(t('flashSeparate'))}</p>`);
 if(supportPage==='report'){const reportSnap=reportArtifact&&reportDetailsSnapshot?reportDetailsSnapshot:snap;return supportFrame('reportTitle',`<p class="support-note">${esc(t('reportNote'))}</p><p class="support-note demo-scope">${esc(t('reportNotCard'))}</p>${statusRows([['versionLabel',snap.app.version+' · '+snap.app.build],['reportEvents',reportSnap.diagnostics.eventCount+' / '+reportSnap.diagnostics.eventLimit]])}<p class="report-download-status ${reportStatus==='error'?'error':''}" role="status">${esc(t({idle:'reportReady',requested:'reportRequested',error:'reportFailed'}[reportStatus]))}</p>${reportArtifact?`<p class="report-filename">${esc(t('reportFilename'))}: ${esc(reportArtifact.filename)}</p>`:''}<div class="support-list"><button class="support-row ${settingsControl==='download'?'focused':''}" data-setting="download" data-support="download"><span><b>${esc(t(reportArtifact?'downloadAgain':'downloadTxt'))}</b></span>${icon('right',16)}</button><button class="support-row compact ${settingsControl==='new-snapshot'?'focused':''}" data-setting="new-snapshot" data-support="new-snapshot"><span><b>${esc(t('reportNew'))}</b><small>${esc(t('reportNewHint'))}</small></span>${icon('plus',16)}</button></div>`);}
 if(supportPage==='about')return supportFrame('aboutTitle',`<div class="support-readout" data-scroll="support" tabindex="0"><h2>Film Lab <small>${esc(t('previewLabel'))}</small></h2>${statusRows([['versionLabel','0.4 · '+t('previewLabel')],['buildLabel',snap.app.build],['targetLabel',snap.device.targetModel],['validationLabel',t('notValidated')]])}<p class="support-note">${esc(t('aboutNote'))}</p><p class="support-url"><b>${esc(t('projectLabel'))}</b><br>github.com/yangzhloop/sony-pmca-film-lab</p>${supportRow('licenses','sourcesAction','','licenses')}</div>`);
 if(supportPage==='licenses')return supportFrame('licensesTitle',`<div class="support-readout" data-scroll="support" tabindex="0"><h2>MIT</h2><p>Copyright © 2026 Yang zhi</p><p>${esc(t('licensesNote'))}</p><p class="support-url">github.com/yangzhloop/sony-pmca-film-lab/blob/main/LICENSE</p><h3>${esc(t('referencesLabel'))}</h3><p>${esc(t('referencesNote'))}</p><p class="support-url">github.com/ukiki0718-netizen/sony-a5100-film-studio<br>github.com/ma1co/OpenMemories-Framework<br>github.com/ma1co/Sony-PMCA-RE</p><p>${esc(t('futureLicenseNote'))}</p></div>`);
 if(supportPage==='developer')return supportFrame('developerTitle',`<p class="support-note demo-scope">${esc(t('developerNote'))}</p><div class="support-list">${supportRow('device','deviceTitle')}${supportRow('processing','processingTitle')}${supportRow('keys','keysTitle')}${supportRow('events','eventsTitle')}</div>`);
 if(supportPage==='device')return supportFrame('deviceTitle',`<div class="support-readout" data-scroll="support" tabindex="0"><p class="support-note demo-scope">${esc(t('browserScope'))}</p>${statusRows([['targetLabel',snap.device.targetModel],['actualModel',snap.device.actualModel||t('notConnected')],['firmwareLabel',snap.device.firmware||t('unavailable')],['apiLabel',snap.device.platformApi||t('unavailable')],['matrixLabel',t('unavailable')],['gammaLabel',t('unavailable')],['nativeReadback',t('unavailable')],['cameraCard',t('unavailable')]])}</div>`);
 if(supportPage==='processing')return supportFrame('processingTitle',`<div class="support-readout" data-scroll="support" tabindex="0"><p class="support-note demo-scope">${esc(t('processingNote'))}</p>${statusRows([['activeRecipe',recipeSummary(snap.recipes.active)],['savedRecipe',recipeSummary(snap.recipes.savedActive)],['highlightedRecipe',recipeSummary(snap.recipes.highlighted)],['draftRecipe',recipeSummary(snap.recipes.draft)],['displayState',t({info:'displayInfo',clean:'displayClean',histogram:'displayHistogram',level:'displayLevel'}[snap.ui.displayMode])],['flashMode',t({front:'frontSync',slow:'slowSync',rear:'rearSync'}[snap.flash.mode])+' · '+flashEv()],['matrixLabel',snap.camera.matrix==='unavailable'?t('unavailable'):snap.camera.matrix],['gammaLabel',snap.camera.curve==='unavailable'?t('unavailable'):snap.camera.curve],['captureLabel',snap.camera.capture==='unavailable'?t('unavailable'):snap.camera.capture]])}</div>`);
 if(supportPage==='keys')return supportFrame('keysTitle',`<p class="support-note demo-scope">${esc(t('keysNote'))}</p><div class="support-readout event-readout" data-scroll="keys" tabindex="0">${keyEvents.length?keyEvents.slice().reverse().map(event=>`<div class="key-event"><b>#${event.sequence}</b><span>${esc(keyLabel(event.control))}</span><small>${esc(t({down:'keyDown',up:'keyUp',press:'keyPress'}[event.phase]))}${event.repeat?' · '+esc(t('keyRepeat')):''}</small></div>`).join(''):`<p class="support-empty">${esc(t('keyEmpty'))}</p>`}</div>`);
 if(supportPage==='events')return supportFrame('eventsTitle',`<p class="support-note">${esc(t('eventsNote'))}</p><div class="support-readout event-readout" data-scroll="events" tabindex="0">${diagnostics.getEvents().length?diagnostics.getEvents().slice().reverse().map(event=>`<div class="app-event"><span>#${event.sequence} · ${event.elapsedMs} ms</span><b>${esc(event.type)}</b><code>${esc(JSON.stringify(event.details))}</code></div>`).join(''):`<p class="support-empty">${esc(t('eventsEmpty'))}</p>`}</div>`);
 return supportFrame('settings','');
}
function modal(){const m=state.modal;if(!m)return '';const r=record(m.id);return `<div class="modal-shade"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="dialog-title"><h2 id="dialog-title">${esc(t(m.type==='rename'?'renameTitle':m.type==='delete'?'deleteTitle':'discardTitle'))}</h2>${m.type==='rename'?`<input id="rename-input" aria-label="${esc(t('recipeName'))}" data-rename value="${esc(m.value)}" autocomplete="off"><p class="field-note">${esc(t('nameHint'))}</p>`:`<p>${r?`<span class="modal-name">${esc(name(r))}</span>`:''}${esc(t(m.type==='delete'?(m.id===state.active.id?'deleteActiveBody':'deleteBody'):'discardBody'))}</p>`}<div class="modal-buttons">${btn('cancelModal','cancel')}${btn(m.type==='rename'?'confirmRename':m.type==='delete'?'confirmDelete':'discard',m.type==='rename'?'save':m.type==='delete'?'delete':'discard','',{},m.type==='delete'?'danger':'primary')}</div><p class="field-note">${esc(t('hintDialog'))}</p></section></div>`;}
function shell(){return `<header class="page-head"><div class="brand"><div class="brand-mark">${film({},28)}</div><div><div class="brand-name">Film Lab</div><div class="brand-caption">SONY A7R II / ${esc(t('copyright'))}</div></div></div><div class="language-switch" role="group" aria-label="Language / 语言"><button class="${state.lang==='en'?'chosen':''}" data-action="language" data-payload='{"lang":"en"}'>EN</button><span>/</span><button class="${state.lang==='zh'?'chosen':''}" data-action="language" data-payload='{"lang":"zh"}'>中文</button></div></header><section class="intro"><div><div class="eyebrow">${esc(t('review'))}</div><h1>${esc(t('title'))}</h1><p>${esc(t('subtitle'))}</p></div><span class="version">v0.4 / ${esc(t('reviewStatus'))}</span></section><main class="workspace"><section><div class="camera"><div class="camera-main"><div class="viewport"><div id="lcd" class="lcd" tabindex="-1" aria-label="${esc(t('titleCamera'))}"></div></div><div class="hardware"><div class="shutter-wrap"><button class="shutter" data-ui="capture" aria-label="${esc(t('shutter'))}"></button><span>${esc(t('shutter'))}</span></div><button data-ui="menu">MENU</button><button data-ui="fn" aria-label="Fn · ${esc(t('filmPresets'))}">Fn</button><div class="wheel"><button class="up" data-nav="up" aria-label="${esc(t('up'))}">▲<span class="disp-label">DISP</span></button><button class="left" data-nav="left" aria-label="${esc(t('left'))}">◀</button><button class="center" data-nav="center" aria-label="${esc(t('select'))}">●</button><button class="right" data-nav="right" aria-label="${esc(t('right'))}">▶</button><button class="down" data-nav="down" aria-label="${esc(t('down'))}">▼</button></div><button data-action="halfShutter">AF / ${state.lang==='zh'?'半按':'HALF'}</button></div></div><div class="camera-signature"><b>α7R II</b><span>FILM LAB · ${esc(t('sample'))}</span></div></div><div class="outside-hint"><span>${esc(t('navigation'))}</span><span data-key-hint>${esc(t('keys'))}</span></div><div class="mobile-controls"><button data-ui="menu">MENU</button><button data-ui="fn">Fn</button><button data-ui="disp" aria-label="DISP">DISP</button><button data-nav="left" aria-label="${esc(t('left'))}">←</button><button data-nav="up" aria-label="${esc(t('up'))}">↑</button><button data-nav="down" aria-label="${esc(t('down'))}">↓</button><button data-nav="right" aria-label="${esc(t('right'))}">→</button><button data-nav="center" aria-label="${esc(t('select'))}">●</button><button data-ui="capture">${esc(t('shutter'))}</button></div></section><aside class="sidebar"><h2 class="side-title">${esc(t('walkthrough'))}</h2><div class="steps">${[['browse','reviewStep1','stepBrowse'],['create','reviewStep2','stepCreate'],['manage','reviewStep3','stepManage'],['language','reviewStep4','stepLanguage']].map(([action,title,desc],i)=>`<button class="step" data-go="${action}"><span class="step-num">0${i+1}</span><span><strong>${esc(t(title))} ↗</strong><small>${esc(t(desc))}</small></span></button>`).join('')}</div><div class="legend">${film({code:'CC',accent:'#bd9c6d'},35)}<div><b>${esc(t('layout'))}</b>${esc(t('layoutText'))}</div>${film({code:'CC',origin:'user',accent:'#bd9c6d'},35)}<div><b>${esc(t('identity'))}</b>${esc(t('identityText'))}</div></div><p class="review-note">${esc(t('reviewPrompt'))}</p></aside></main><footer class="bottom-note"><span>${esc(t('prototypeNote'))}<br>${esc(t('physical'))}</span><span>${esc(t('footer'))}</span></footer>`;}
function isKeyViewer(){return state.screen==='settings'&&supportPage==='keys';}
function supportDefault(page){return {index:'flash',flash:'flash-mode',camera:'focus-mode','focus-mode':focusDemo.mode,'focus-area':focusDemo.area,language:state.lang,report:'download',about:'licenses',developer:'device'}[page]||'';}
function openSupportPage(page){
 if(state.screen!=='settings')return;
 if(!['flash','camera','focus-mode','focus-area','language','report','about','licenses','developer','device','processing','keys','events'].includes(page))return;
 supportHistory.push({page:supportPage,control:settingsControl});supportPage=page;settingsControl=supportDefault(page);
 if(page==='keys'){keyEvents=[];keySequence=0;}
 diagnostics.record('ui.open',{action:'open-page',page});render();
}
function supportBack(){
 if(supportHistory.length){const parent=supportHistory.pop();supportPage=parent.page;settingsControl=parent.control;diagnostics.record('ui.open',{page:supportPage});render();}
 else if(supportPage!=='index'){supportPage='index';settingsControl='flash';render();}
 else dispatch('back');
}
function adjustFlash(kind,change){
 if(kind==='mode'){const modes=['front','slow','rear'];flashDemo.mode=modes[(modes.indexOf(flashDemo.mode)+change+modes.length)%modes.length];settingsControl='flash-mode';}
 else {flashDemo.compensationSteps=Math.max(-9,Math.min(9,flashDemo.compensationSteps+change));settingsControl='flash-compensation';}
 diagnostics.record('ui.field',{action:settingsControl,field:settingsControl,flash:flashState()});render();
}
function selectFocus(kind,value){
 if(!focusOptions[kind]||!focusOptions[kind].some(([option])=>option===value)||supportPage!=='focus-'+kind)return;
 focusDemo[kind]=value;
 diagnostics.record('ui.field',{action:'focus-'+kind,field:'focus-'+kind,page:supportPage});
 supportBack();
}
let reportStateSnapshot=null,reportContextSnapshot=null,reportDetailsSnapshot=null;
function downloadReport(){
 try{
  if(!reportArtifact){
   diagnostics.record('report.snapshot',{page:'report',status:'created'});
   reportStateSnapshot=copy(state);reportContextSnapshot=copy(supportContext());
   reportDetailsSnapshot=diagnostics.snapshot(reportStateSnapshot,reportContextSnapshot);
   reportArtifact=diagnostics.createReport(reportStateSnapshot,reportContextSnapshot);
  }
  const blob=new Blob([reportArtifact.text],{type:'text/plain;charset=utf-8'});
  const url=URL.createObjectURL(blob),link=document.createElement('a');
  link.href=url;link.download=reportArtifact.filename;link.hidden=true;document.body.appendChild(link);
  try{link.click();}finally{link.remove();setTimeout(()=>URL.revokeObjectURL(url),1500);}
  reportStatus='requested';diagnostics.record('report.download.requested',{action:'report-download',page:'report',status:'requested'});
 }catch(error){reportStatus='error';diagnostics.record('report.download.failed',{action:'report-download',page:'report',status:'failed'});}
 render();
}
function newReportSnapshot(){reportArtifact=null;reportStateSnapshot=null;reportContextSnapshot=null;reportDetailsSnapshot=null;reportStatus='idle';settingsControl='download';render();}
function keyLabel(control){return control==='printable'?t('keyRedacted'):control==='other'?(state.lang==='zh'?'其他按键':'Other key'):control==='half'?t('half'):control==='shutter'?t('shutter'):control==='fn'?'Fn':control==='disp'?'DISP':control==='menu'?'MENU':t(control==='center'?'select':control);}
function observeControl(control,phase='press',repeat=false){
 if(!isKeyViewer())return;
 keyEvents.push({sequence:++keySequence,control,phase,repeat});if(keyEvents.length>48)keyEvents.shift();
 diagnostics.record('ui.key',{control,phase,repeat,page:'keys'});render();if(control==='up'||control==='down')scrollSupport(control);
}
function keyboardControl(event){
 if(event.key.length===1)return 'printable';
 return {ArrowUp:'up',ArrowDown:'down',ArrowLeft:'left',ArrowRight:'right',Enter:'center',Escape:'menu',Tab:'tab'}[event.key]||'other';
}
function buttonControl(button){return button.dataset.nav||{menu:'menu',fn:'fn',disp:'disp',capture:'shutter'}[button.dataset.ui]||(button.dataset.action==='halfShutter'?'half':'other');}
function scrollSupport(direction){
 const readout=document.querySelector('#lcd .support-readout');if(readout)readout.scrollTop+=direction==='up'?-48:48;
}
function navigateSupport(dir){
 if(dir==='left'&&supportPage!=='flash'){supportBack();return;}
 if(isKeyViewer()){observeControl(dir);return;}
 const rows=settingButtons();
 if(!rows.length){if(dir==='up'||dir==='down')scrollSupport(dir);return;}
 let index=rows.findIndex(x=>x.dataset.setting===settingsControl);if(index<0)index=0;
 if(dir==='up'||dir==='down'){
  if(supportPage==='about')scrollSupport(dir);
  else {index=(index+(dir==='down'?1:-1)+rows.length)%rows.length;settingsControl=rows[index].dataset.setting;rows[index].focus({preventScroll:true});rows[index].scrollIntoView({block:'nearest'});markControlFocus();}
  return;
 }
 if((dir==='left'||dir==='right')&&supportPage==='flash'){adjustFlash(settingsControl==='flash-mode'?'mode':'compensation',dir==='right'?1:-1);return;}
 if(dir==='center')activateButton(rows[index]);
}

let shellLang=null,renderedSupportPage=null;
function focusToken(element){return element?{id:element.id,action:element.dataset.action,payload:element.getAttribute('data-payload'),control:element.closest('[data-control]')?.dataset.control,setting:element.closest('[data-setting]')?.dataset.setting,step:element.dataset.step,change:element.dataset.change}:null;}
function controlElement(key,scope=document.getElementById('lcd')){return Array.from(scope.querySelectorAll('[data-control]')).find(x=>x.dataset.control===key);}
function findFocus(token,scope){
 if(!token)return null;
 const byId=token.id?document.getElementById(token.id):null;if(byId&&byId!==scope&&scope.contains(byId))return byId;
 if(token.setting){const setting=Array.from(scope.querySelectorAll('[data-setting]')).find(x=>x.dataset.setting===token.setting);if(setting)return setting;}
 if(token.action){const action=Array.from(scope.querySelectorAll('[data-action]')).find(x=>x.dataset.action===token.action&&x.getAttribute('data-payload')===token.payload);if(action)return action;}
 if(token.control){const control=controlElement(token.control,scope);if(control)return control;}
 if(token.step)return Array.from(scope.querySelectorAll('[data-step]')).find(x=>x.dataset.step===token.step&&x.dataset.change===token.change);
 return null;
}
function editorRows(){return Array.from(document.querySelectorAll('#lcd .editor-fields > .field[data-control],#lcd .editor-bottom[data-control]'));}
function saveButtons(){return Array.from(document.querySelectorAll('#lcd .editor-bottom [data-action=save]'));}
function detailButtons(){return Array.from(document.querySelectorAll('#lcd .detail-actions button'));}
function settingButtons(){return Array.from(document.querySelectorAll('#lcd [data-setting]'));}
function modalButtons(){return Array.from(document.querySelectorAll('#lcd .modal-buttons button'));}
function defaultFocus(lcd){
 if(state.modal)return state.modal.type==='rename'?lcd.querySelector('#rename-input'):modalButtons()[0];
 if(state.screen==='editor')return editorControl==='save-actions'?saveButtons()[editorSaveIndex]:controlElement(editorControl)||controlElement('name');
 if(state.screen==='detail')return detailButtons()[detailActionIndex]||detailButtons()[0];
 if(state.screen==='settings')return settingButtons().find(x=>x.dataset.setting===settingsControl)||settingButtons()[0]||lcd.querySelector('.support-readout')||lcd;
 if(state.screen==='library')return libraryNewFocused?lcd.querySelector('.new-row'):lcd.querySelector('.recipe-row.selected,.new-row')||lcd;
 return lcd;
}
function markControlFocus(){
 document.querySelectorAll('#lcd .field[data-control]').forEach(x=>x.classList.toggle('focused',x.dataset.control===editorControl));
 saveButtons().forEach((x,i)=>x.classList.toggle('focused',editorControl==='save-actions'&&editorSaveIndex===i));
 settingButtons().forEach(x=>x.classList.toggle('focused',x.dataset.setting===settingsControl));
}
function screenHint(){if(state.screen==='settings'&&!state.modal)return supportPage==='keys'?'hintKeys':supportPage==='flash'?'hintFlash':supportPage==='report'?'hintReport':['focus-mode','focus-area'].includes(supportPage)?'hintSettings':['device','processing','events','licenses'].includes(supportPage)?'hintReadout':'hintSupport';return state.modal?'hintDialog':{shoot:'hintShoot',library:'hintLibrary',editor:'hintEditor',detail:'hintDetail',settings:'hintSettings'}[state.screen];}
function syncControls(){
 document.querySelectorAll('.hardware [data-nav=up]').forEach(x=>x.setAttribute('aria-label',t(state.screen==='shoot'?'disp':'up')));
 document.querySelectorAll('[data-ui=disp],[data-ui=fn]').forEach(x=>{x.disabled=!isKeyViewer()&&(state.screen!=='shoot'||!!state.modal);});
 document.querySelectorAll('.hardware [data-nav=left],.hardware [data-nav=right],.mobile-controls [data-nav=left],.mobile-controls [data-nav=right]').forEach(x=>{x.title=state.screen==='shoot'?t('nativeDirection'):'';});
 const hint=document.querySelector('[data-key-hint]');if(hint)hint.textContent=t(screenHint());
}
function render(){
 const previous=document.activeElement,previousLcd=document.getElementById('lcd');
 const previousToken=previousLcd?.contains(previous)?focusToken(previous):null;
 const openingModal=!!state.modal&&!renderedModal,closingModal=!state.modal&&renderedModal;
 const supportPageChanged=state.screen==='settings'&&supportPage!==renderedSupportPage;
 if(openingModal){modalReturnFocus=previousToken||lastLcdFocus;dialogActionIndex=0;}
 if(state.screen!=='library'||state.tab!=='user')libraryNewFocused=false;
 const scrolls={};document.querySelectorAll('[data-scroll]').forEach(x=>scrolls[x.dataset.scroll]=x.scrollTop);
 if(shellLang!==state.lang){document.getElementById('app').innerHTML=shell();shellLang=state.lang;document.documentElement.lang=state.lang==='zh'?'zh-CN':'en';}
 const lcd=document.getElementById('lcd');
 lcd.dataset.displayMode=displayMode;lcd.dataset.screen=state.screen;
 lcd.dataset.supportPage=state.screen==='settings'?supportPage:'';lcd.dataset.settingsPage=lcd.dataset.supportPage;
 const clean=state.screen==='shoot'&&displayMode==='clean';
 lcd.innerHTML=({shoot:shooting,library,detail,editor,settings}[state.screen]||shooting)()+modal()+(!clean&&(state.notice||transient)?`<div class="toast ${state.notice==='nameRequired'||state.notice==='nameLong'?'error':''}" role="status">${esc(state.notice==='saved'?t('savedOnly'):t(state.notice||transient))}</div>`:'')+(!clean&&flash?'<div class="flash"></div>':'');
 document.querySelectorAll('[data-scroll]').forEach(x=>{x.scrollTop=scrolls[x.dataset.scroll]||0;});
 if(state.screen==='editor'&&!controlElement(editorControl))editorControl='name';
 let next=openingModal?defaultFocus(lcd):closingModal?findFocus(modalReturnFocus,lcd):supportPageChanged?defaultFocus(lcd):findFocus(previousToken,lcd);
 if(state.modal&&next&&!next.closest('.modal'))next=null;
 if(!next||next.disabled)next=defaultFocus(lcd);
 next?.focus({preventScroll:true});
 markControlFocus();syncControls();
 renderedModal=!!state.modal;renderedSupportPage=state.screen==='settings'?supportPage:null;if(closingModal)modalReturnFocus=null;
 scale();
}
function scale(){const viewport=document.querySelector('.viewport');if(viewport)document.getElementById('lcd').style.transform=`scale(${viewport.clientWidth/640})`;}
model.subscribe(next=>{
 const oldScreen=state.screen,oldDraftId=state.draft?.id;
 if(next.screen==='editor'&&(oldScreen!=='editor'||oldDraftId!==next.draft?.id)){editorControl='name';editorSaveIndex=0;}
 if(next.screen==='detail'&&oldScreen!=='detail')detailActionIndex=0;
 if(next.screen==='settings'&&oldScreen!=='settings'){supportPage='index';supportHistory=[];settingsControl='flash';}
 state=next;if(!textEditing)render();clearTimeout(noticeTimer);
 if(state.notice)noticeTimer=setTimeout(()=>model.dispatch('dismissNotice'),3500);
});
function dispatch(action,payload={}){
 transient='';if(['select','tab','openLibrary','detail','create','duplicate','edit','save','discard','confirmDelete'].includes(action))libraryNewFocused=false;
 if(!(action==='field'&&payload.key==='name')&&action!=='renameValue')diagnostics.record(action==='field'?'ui.field':action==='language'?'ui.language':'ui.action',{action,field:payload.key,numericValue:typeof payload.value==='number'?payload.value:undefined,enumValue:payload.key==='wb'?payload.value:undefined,baseId:payload.key==='baseId'?payload.value:undefined,lang:payload.lang,tab:payload.tab,id:payload.id,use:payload.use,screen:state.screen,page:state.screen==='settings'?supportPage:state.screen});
 model.dispatch(action,payload);
}
function cycleDisplay(){
 if(state.screen!=='shoot'||state.modal)return;
 displayMode=displayModes[(displayModes.indexOf(displayMode)+1)%displayModes.length];
 diagnostics.record('ui.display',{action:'display',displayMode});
 try{window.localStorage.setItem(DISPLAY_STORAGE_KEY,displayMode);}catch(error){}
 render();
}
function menu(){if(state.screen==='settings'&&!state.modal){supportBack();return;}dispatch(state.screen==='shoot'&&!state.modal?'settings':'back');}
function fn(){if(state.screen==='shoot'&&!state.modal)dispatch('openLibrary');}
function capture(){
 if(state.screen!=='shoot'){dispatch('halfShutter');return;}
 diagnostics.record('simulated.capture',{action:'capture',status:'simulated'});flash=true;transient='shotSimulated';render();
 setTimeout(()=>{flash=false;render();},300);setTimeout(()=>{transient='';render();},2800);
}
function go(kind){if(state.screen==='editor'){dispatch('back');return;}if(kind==='browse'){dispatch('openLibrary');dispatch('tab',{tab:'builtin'});}else if(kind==='create')dispatch('duplicate',{id:state.selectedId&&record(state.selectedId)?state.selectedId:'classic-chrome'});else if(kind==='manage'){dispatch('openLibrary');dispatch('tab',{tab:'user'});}else if(kind==='language'){dispatch('settings');openSupportPage('language');}}
function activateButton(button){
 if(!button||button.disabled)return;
 if(isKeyViewer()){
  if(button.dataset.ui==='menu'||button.dataset.action==='back'){observeControl('menu');supportBack();return;}
  if(button.closest('.hardware,.mobile-controls')){observeControl(buttonControl(button));return;}
 }
 if(button.dataset.support){
  if(button.dataset.setting)settingsControl=button.dataset.setting;
  if(button.dataset.support==='open')openSupportPage(button.dataset.page);
  else if(button.dataset.support==='download')downloadReport();
  else if(button.dataset.support==='new-snapshot')newReportSnapshot();
  else if(button.dataset.support==='flash-adjust')adjustFlash(button.dataset.flash,Number(button.dataset.change));
  else if(button.dataset.support==='focus-select')selectFocus(button.dataset.focus,button.dataset.value);
  return;
 }
 if(button.dataset.nav){navigate(button.dataset.nav);return;}
 if(button.dataset.ui){({capture,menu,fn,disp:cycleDisplay}[button.dataset.ui])?.();return;}
 if(button.dataset.go){go(button.dataset.go);return;}
 if(button.dataset.action){if(button.dataset.action==='back'){menu();return;}if(button.dataset.setting)settingsControl=button.dataset.setting;dispatch(button.dataset.action,button.dataset.payload?JSON.parse(button.dataset.payload):{});return;}
 if(button.dataset.step){editorControl=button.dataset.step;dispatch('field',{key:button.dataset.step,value:state.draft.settings[button.dataset.step]+Number(button.dataset.change)});focusEditorControl();}
}
function focusEditorControl(){
 const row=controlElement(editorControl);if(!row)return;
 const target=editorControl==='save-actions'?saveButtons()[editorSaveIndex]:row;
 target?.focus({preventScroll:true});row.scrollIntoView({block:'nearest',inline:'nearest'});markControlFocus();
}
function navigateEditor(dir){
 const rows=editorRows();if(!rows.length)return;
 let index=rows.findIndex(x=>x.dataset.control===editorControl);if(index<0)index=0;
 if(dir==='up'||dir==='down'){
  editorControl=rows[(index+(dir==='down'?1:-1)+rows.length)%rows.length].dataset.control;focusEditorControl();return;
 }
 const row=controlElement(editorControl);if(!row)return;
 if(dir==='left'||dir==='right'){
  const change=dir==='right'?1:-1;
  if(editorControl==='save-actions'){editorSaveIndex=dir==='right'?1:0;focusEditorControl();return;}
  if(editorControl==='name'){
   const input=document.getElementById('recipe-name');
   if(document.activeElement===input){const position=Math.max(0,Math.min(input.value.length,(change<0?input.selectionStart:input.selectionEnd)+change));input.setSelectionRange(position,position);}
   return;
  }
  if(editorControl==='baseId'||editorControl==='wb'){
   const options=editorControl==='baseId'?state.records.filter(x=>x.origin==='builtin').map(x=>x.id):['auto','kelvin'];
   const value=editorControl==='baseId'?state.draft.baseId:state.draft.settings.wb;
   dispatch('field',{key:editorControl,value:options[(options.indexOf(value)+change+options.length)%options.length]});focusEditorControl();return;
  }
  if(row.dataset.stepSize){const value=Math.max(Number(row.dataset.min),Math.min(Number(row.dataset.max),state.draft.settings[editorControl]+change*Number(row.dataset.stepSize)));dispatch('field',{key:editorControl,value});focusEditorControl();}
  return;
 }
 if(dir==='center'){
  if(editorControl==='save-actions'){activateButton(saveButtons()[editorSaveIndex]);return;}
  if(editorControl==='name'){
   const input=document.getElementById('recipe-name');
   if(document.activeElement===input)focusEditorControl();else {input.focus();input.setSelectionRange(input.value.length,input.value.length);}
  }else focusEditorControl();
 }
}
function navigateModal(dir){
 const buttons=modalButtons();if(!buttons.length)return;
 if(dir==='left'||dir==='right'){dialogActionIndex=dir==='left'?0:buttons.length-1;buttons[dialogActionIndex].focus();return;}
 if(dir==='up'||dir==='down'){
  const items=Array.from(document.querySelectorAll('#lcd .modal input,#lcd .modal-buttons button'));
  const index=items.indexOf(document.activeElement);items[(Math.max(index,0)+(dir==='down'?1:-1)+items.length)%items.length]?.focus();return;
 }
 if(dir==='center'){
  if(document.activeElement?.matches('#rename-input')){dialogActionIndex=buttons.length-1;buttons[dialogActionIndex].focus();return;}
  activateButton(buttons[dialogActionIndex]||buttons[0]);
 }
}
function navigate(dir){
 if(isKeyViewer()){observeControl(dir);return;}
 diagnostics.record('ui.key',{control:dir,phase:'press',page:state.screen==='settings'?supportPage:state.screen});
 if(state.modal){navigateModal(dir);return;}
 // Browser-only wheel-top simulation. The camera port must preserve Sony's
 // dedicated DISP event/converter/controller; generic Up is a different event.
 // These four illustrative views do not prescribe native enabled modes/order.
 if(state.screen==='shoot'){if(dir==='up')cycleDisplay();else if(dir==='center')fn();return;}
 if(state.screen==='editor'){navigateEditor(dir);return;}
 if(state.screen==='detail'){
  const actions=detailButtons();if(!actions.length)return;
  if(dir==='left'){dispatch('back');return;}
  if(dir==='up'||dir==='down')detailActionIndex=(detailActionIndex+(dir==='down'?1:-1)+actions.length)%actions.length;
  if(dir==='center'){activateButton(actions[detailActionIndex]||actions[0]);return;}
  actions[detailActionIndex]?.focus();return;
 }
 if(state.screen==='settings'){navigateSupport(dir);return;}
 if(state.screen==='library'){
  const rows=visible(),hasNew=state.tab==='user',total=rows.length+(hasNew?1:0),focused=document.activeElement;
  const focusedRow=focused?.closest('.recipe-row'),onNew=hasNew&&(libraryNewFocused||focused?.matches('.new-row'));
  let index=onNew?rows.length:rows.findIndex(r=>r.id===(focusedRow?.dataset.id||state.selectedId));
  if(dir==='up'||dir==='down'){
   if(!total)return;index=index<0?(dir==='down'?0:total-1):(index+(dir==='down'?1:-1)+total)%total;
   let target;
   if(index===rows.length){libraryNewFocused=true;render();target=document.querySelector('.new-row');}
   else {dispatch('select',{id:rows[index].id});target=Array.from(document.querySelectorAll('.recipe-row')).find(x=>x.dataset.id===rows[index].id);}
   target?.focus({preventScroll:true});target?.scrollIntoView({block:'nearest'});return;
  }
  if(dir==='left'||dir==='right'){const tabs=libraryTabs.map(([id])=>id);dispatch('tab',{tab:tabs[(tabs.indexOf(state.tab)+(dir==='right'?1:2))%3]});return;}
  if(dir==='center'){if(onNew||(!rows.length&&hasNew))dispatch('create');else if(index>=0)dispatch('detail',{id:rows[index].id});}
 }
}
document.addEventListener('pointerdown',e=>{if(e.target.closest('.hardware button,.mobile-controls button'))e.preventDefault();});
document.addEventListener('focusin',e=>{
 const lcd=document.getElementById('lcd');if(!lcd?.contains(e.target))return;
 lastLcdFocus=focusToken(e.target);
 const row=e.target.closest('[data-control]');if(row){editorControl=row.dataset.control;if(editorControl==='save-actions'){const index=saveButtons().indexOf(e.target);if(index>=0)editorSaveIndex=index;}}
 const actionIndex=detailButtons().indexOf(e.target);if(actionIndex>=0)detailActionIndex=actionIndex;
 const setting=e.target.closest('[data-setting]');if(setting)settingsControl=setting.dataset.setting;
 const modalIndex=modalButtons().indexOf(e.target);if(modalIndex>=0)dialogActionIndex=modalIndex;
 markControlFocus();
});
document.addEventListener('click',e=>{
 const setting=e.target.closest('#lcd [data-setting]');if(setting){settingsControl=setting.dataset.setting;markControlFocus();if(setting.classList.contains('support-flash-row')&&!e.target.closest('button'))setting.focus();}
 const row=e.target.closest('#lcd .field[data-control]');if(row){editorControl=row.dataset.control;markControlFocus();if(!e.target.closest('input,select,button'))focusEditorControl();}
 activateButton(e.target.closest('button'));
});
document.addEventListener('dblclick',e=>{const row=e.target.closest('.recipe-row');if(row)dispatch('detail',{id:row.dataset.id});});
document.addEventListener('input',e=>{if(e.target.dataset.field==='name'||e.target.hasAttribute('data-rename')){textEditing=true;model.dispatch(e.target.hasAttribute('data-rename')?'renameValue':'field',e.target.hasAttribute('data-rename')?{value:e.target.value}:{key:'name',value:e.target.value});textEditing=false;}});
document.addEventListener('change',e=>{if(e.target.tagName==='SELECT'&&e.target.dataset.field){editorControl=e.target.dataset.field;dispatch('field',{key:e.target.dataset.field,value:e.target.value});}});
document.addEventListener('keydown',e=>{
 if(e.ctrlKey||e.metaKey||e.altKey)return;
 if(isKeyViewer()){
  e.preventDefault();const button=e.target.closest('button');
  const isMenu=event=>event.key==='Escape'||(event.key==='Enter'&&button&&(button.dataset.ui==='menu'||button.dataset.action==='back'));
  observeControl(isMenu(e)?'menu':keyboardControl(e),'down',e.repeat);
  if(isMenu(e))supportBack();return;
 }
 if(e.isComposing)return;
 const typing=e.target.matches('input,textarea');
 if(e.key==='Escape'){
  e.preventDefault();
  if(state.screen==='editor'&&!state.modal&&e.target.matches('#recipe-name')){editorControl='name';focusEditorControl();}
  else menu();
  return;
 }
 if(e.key==='Tab'&&state.modal){
  const items=Array.from(document.querySelectorAll('.modal button:not(:disabled),.modal input,.modal select')).filter(x=>x.offsetParent!==null);
  const index=items.indexOf(document.activeElement);e.preventDefault();items[index<0?(e.shiftKey?items.length-1:0):(index+(e.shiftKey?-1:1)+items.length)%items.length]?.focus();return;
 }
 if(typing&&(e.key==='ArrowLeft'||e.key==='ArrowRight'))return;
 if(typing&&!['ArrowUp','ArrowDown','Enter'].includes(e.key))return;
 if(['ArrowUp','ArrowDown','ArrowLeft','ArrowRight'].includes(e.key)){e.preventDefault();navigate(e.key.replace('Arrow','').toLowerCase());return;}
 if(e.key==='Enter'){
  e.preventDefault();
  const button=e.target.closest('button');
  if(button?.matches('.recipe-row')&&state.screen==='library'&&!state.modal)dispatch('detail',{id:button.dataset.id});
  else if(button)activateButton(button);else navigate('center');return;
 }
 if(e.key===' '){e.preventDefault();const button=e.target.closest('button');if(button)activateButton(button);else if(state.screen==='shoot')capture();return;}
 if(e.key.toLowerCase()==='d'){if(state.screen==='shoot'&&!state.modal){e.preventDefault();cycleDisplay();}return;}
 if(e.key.toLowerCase()==='f'){if(state.screen==='shoot'&&!state.modal){e.preventDefault();fn();}return;}
 if(e.key.toLowerCase()==='h'){e.preventDefault();dispatch('halfShutter');}
});
document.addEventListener('keyup',e=>{if(isKeyViewer()&&!e.ctrlKey&&!e.metaKey&&!e.altKey){e.preventDefault();observeControl(keyboardControl(e),'up',e.repeat);}});
window.addEventListener('resize',scale);render();dispatch('openLibrary');
})();

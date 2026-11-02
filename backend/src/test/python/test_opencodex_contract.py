"""从旧转换器迁移的输入语义契约；准入只校验，不转换请求。"""
import copy
import json
from pathlib import Path
import sys
import pytest
sys.path.insert(0, str(Path(__file__).parents[2] / 'main/resources/agentruntime'))
from opencodex_guard import validate_request, PATCH_GRAMMAR


def valid():
    return {'model':'m', 'tools':[{'type':'function','name':'read','parameters':{'type':'object'}},
            {'type':'custom','name':'apply_patch','format':{'type':'text'}}],
        'input':[{'role':'developer','content':'规则'},
                 {'type':'function_call','call_id':'a','name':'read','arguments':'{}'},
                 {'type':'custom_tool_call','call_id':'b','name':'apply_patch','input':'内容'},
                 {'type':'function_call_output','call_id':'a','output':'ok'},
                 {'type':'custom_tool_call_output','call_id':'b','output':[{'type':'output_text','text':'完成'}]},
                 {'role':'user','content':[{'type':'input_text','text':'继续'}]}]}


@pytest.mark.parametrize('choice', ['auto','none','required',{'type':'custom','name':'apply_patch'}, {'type':'function','name':'read'}])
def test_complete_parallel_history_is_unchanged(choice):
    value=valid(); value['tool_choice']=choice
    value.update(instructions='系统', service_tier='auto', reasoning=None)
    snapshot=copy.deepcopy(value)
    validate_request(value,'m')
    assert value==snapshot
    value['tools'][1]['format']={'type':'grammar','syntax':'lark','definition':PATCH_GRAMMAR}
    validate_request(value,'m')


@pytest.mark.parametrize('extra', [
    {'instructions':[]}, {'service_tier':'priority'}, {'unknown_field':'x'}, {'text':{'verbosity':'low'}},
    {'tools':[{'type':'function','name':'x'}]},
    {'tools':[{'type':'function','name':'x','parameters':{}}]*2},
    {'tool_choice':'bad'}, {'tool_choice':None}, {'tool_choice':{'name':'read'}},
    {'tool_choice':{'type':'function','name':'missing'}},
    {'tool_choice':{'type':'custom','name':'read'}},
])
def test_unsupported_contract_rejected(extra):
    with pytest.raises(ValueError): validate_request({**valid(),**extra},'m')


@pytest.mark.parametrize('mutation', [
    lambda v: v['input'].pop(3),
    lambda v: v['input'].__setitem__(slice(4,None),[]),
    lambda v: v['input'][1].update(name='unknown'),
    lambda v: v['input'][1].update(name='apply_patch'),
    lambda v: v['input'][1].update(arguments='[]'),
    lambda v: v['input'][1].update(arguments='bad'),
    lambda v: v['input'][1].update(call_id=''),
    lambda v: v['input'][2].update(input=42),
    lambda v: v['input'][2].update(call_id='a'),
    lambda v: v['input'][3].update(output={'unsupported':'object'}),
    lambda v: v['input'][3].update(call_id='missing'),
    lambda v: v['input'][3].update(type='custom_tool_call_output'),
    lambda v: v['input'].__setitem__(4,copy.deepcopy(v['input'][3])),
])
def test_broken_tool_associations_rejected(mutation):
    value=valid(); mutation(value)
    with pytest.raises(ValueError): validate_request(value,'m')

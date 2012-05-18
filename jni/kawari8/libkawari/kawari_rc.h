//--------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// リソース
//
//      Programed by Suikyo.
//
//  2002.06.16  Phase 8.0.0   i10nバージョン作成
//
//--------------------------------------------------------------------------
#ifndef KAWARI_RC_H__
#define KAWARI_RC_H__
//--------------------------------------------------------------------------
#include "config.h"
//--------------------------------------------------------------------------
#include <string>
#include <vector>
#include <map>
//--------------------------------------------------------------------------
#if defined(RC)
#	undef RC
#endif
#define RC kawari::resource::ResourceManager
//--------------------------------------------------------------------------
namespace kawari {
//--------------------------------------------------------------------------
namespace resource {
//--------------------------------------------------------------------------
enum ID {
	ID__START = 0,
	ID__NULL = 0,
	ERR_LEXER_EOL_IN_QUOTED_STRING,
	ERR_LEXER_EOF_IN_QUOTED_STRING,

	ERR_COMPILER_UNKNOWN_MODE,
	ERR_COMPILER_NO_ENTRYNAMES,
	ERR_COMPILER_MLENTRYDEF_NOT_CLOSED,
	ERR_COMPILER_INVALID_ENTRYDEF,
	ERR_COMPILER_ILLCHAR_IN_SCRIPT,
	ERR_COMPILER_ENTRYID_NOT_FOUND,
	ERR_COMPILER_FIRST_STATEMENT_NOT_FOUND,
	ERR_COMPILER_STATEMENTLIST_SEPARATOR_NOT_FOUND,
	ERR_COMPILER_INTERNAL_SUBST,
	ERR_COMPILER_INTERNAL_INLINE_SCRIPT,
	ERR_COMPILER_SCRIPT_SUBST_NOT_CLOSED,
	ERR_COMPILER_INTERNAL_BLOCK,
	ERR_COMPILER_BLOCK_NOT_CLOSED,
	ERR_COMPILER_INTERNAL_INDEX,
	ERR_COMPILER_INDEX_BRACKET_NOT_FOUND,
	ERR_COMPILER_INDEX_EXPR_NOT_FOUND,
	ERR_COMPILER_INTERNAL_EXPR,
	ERR_COMPILER_EXPR_NOT_CLOSED,
	ERR_COMPILER_PARSE_ERROR_AFTER,
	ERR_COMPILER_EXPR_BLOCK_NOT_CLOSED,
	ERR_COMPILER_ILLCHAR_IN_EXPRESSION,
	ERR_COMPILER_INTERNAL_ENTRYCALL,
	ERR_COMPILER_ENTRYCALL_NOT_CLOSED,
	ERR_COMPILER_SETEXPR_BLOCK_NOT_CLOSED,

	ERR_CODEEXPR_DIVIDED_BY_ZERO,
	ERR_VM_UNDEFINED_FUNCTION1,
	ERR_VM_UNDEFINED_FUNCTION2,
	ERR_ENGINE_UNKNOWN_MODE,
	ERR_NS_ASSERT_PROTECTED_ENTRY1,
	ERR_NS_ASSERT_PROTECTED_ENTRY2,
	ERR_DICT_CAN_NOT_DELETE_WORD1,
	ERR_DICT_CAN_NOT_DELETE_WORD2,
	ERR_KIS_DICT_INVALID_INDEX,
	ERR_KIS_DICT_ILLEGAL_COPY_OPERATION,
	ERR_KIS_FILE_SAVE_FAILED,
	ERR_KIS_FILE_LOAD_FAILED,
	ERR_KIS_SAORI_CALL_FAILED1,
	ERR_KIS_SAORI_CALL_FAILED2,

	WARN_COMPILER_BLANK_DEFINITION,
	WARN_COMPILER_ENTRYIDLIST_ENDS_WITH_COMMA,
	WARN_NS_READ_EMPTY_ENTRY1,
	WARN_NS_READ_EMPTY_ENTRY2,

	DUMP_DICT_DELETE_WORD1,
	DUMP_DICT_DELETE_WORD2,

	ERR_CODE_BREAK_OUTOF_LOOP,
	ERR_CODE_CONTINUE_OUTOF_LOOP,

	ID__END
};
//--------------------------------------------------------------------------
// I10N
class TResourceManager{
	std::map<std::string,std::string *> rcmap;
	std::string *current;
public:
	TResourceManager(void);
	virtual ~TResourceManager(void) {}
	virtual void SwitchTo(const std::string &charset);
	const std::string &S(kawari::resource::ID id);
};
//--------------------------------------------------------------------------
inline const std::string &TResourceManager::S(kawari::resource::ID id)
{
	// there's no boundary check. be carefull.
	return current[id];
}
//--------------------------------------------------------------------------
extern TResourceManager ResourceManager;
//--------------------------------------------------------------------------
} // resource
//--------------------------------------------------------------------------
} // kawari
//--------------------------------------------------------------------------
#endif // KAWARI_RC_H__
